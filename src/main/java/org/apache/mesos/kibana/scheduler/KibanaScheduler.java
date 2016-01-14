package org.apache.mesos.kibana.scheduler;

import org.apache.mesos.Protos.ExecutorID;
import org.apache.mesos.Protos.Filters;
import org.apache.mesos.Protos.FrameworkID;
import org.apache.mesos.Protos.MasterInfo;
import org.apache.mesos.Protos.Offer;
import org.apache.mesos.Protos.OfferID;
import org.apache.mesos.Protos.Resource;
import org.apache.mesos.Protos.SlaveID;
import org.apache.mesos.Protos.TaskID;
import org.apache.mesos.Protos.TaskInfo;
import org.apache.mesos.Protos.TaskStatus;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The Scheduler for the KibanaFramework, in charge of managing requests, spinning up and killing off tasks to ensure requirements are met.
 */
public class KibanaScheduler implements Scheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(KibanaScheduler.class);
    private final SchedulerConfiguration configuration; // contains the scheduler's settings and tasks

    /**
     * Constructor for KibanaScheduler
     *
     * @param configuration the SchedulerConfiguration to use
     */
    public KibanaScheduler(SchedulerConfiguration configuration) {
        this.configuration = configuration;
    }

    /**
     * Checks whether the given offer has the tasks' required resources
     *
     * @param offer the offer whose resources to check
     * @return a boolean representing whether or not the offer has all the required resources
     */
    protected boolean offerIsAcceptable(Offer offer) {
        boolean hasCPUs = false;
        boolean hasMemory = false;
        boolean hasPorts = false;
        double offeredCPUs = 0;
        double offeredMemory = 0;
        int offeredPortCount = 0;

        for (Resource resource : offer.getResourcesList()) {
            switch (resource.getName()) {
                case "cpus":
                    offeredCPUs = resource.getScalar().getValue();
                    hasCPUs = true;
                    break;
                case "mem":
                    offeredMemory = resource.getScalar().getValue();
                    hasMemory = true;
                    break;
                case "ports":
                    offeredPortCount = Resources.getPortCount(resource);
                    hasPorts = true;
                    break;
            }
        }

        if (!hasCPUs) {
            LOGGER.info("Offer {} does not meet requirements due to lack of cpus ({})", offer.getId().getValue(), offeredCPUs);
            return false;
        }
        if (!hasMemory) {
            LOGGER.info("Offer {} does not meet requirements due to lack of mem ({})", offer.getId().getValue(), offeredMemory);
            return false;
        }
        if (!hasPorts) {
            LOGGER.info("Offer {} does not meet requirements due to lack of ports ({})", offer.getId().getValue(), offeredPortCount);
            return false;
        }

        LOGGER.info("Offer {} is acceptable. (got {} cpus, {} memory and {} ports)", offer.getId().getValue(), offeredCPUs, offeredMemory, offeredPortCount);
        return true;
    }

    /**
     * Launches a new Kibana task for the given elasticSearchUrl, using the offer
     *
     * @param requirement the requirement for the task
     * @param offer       the offer used to run the task
     * @param driver      the driver used to launch the task
     */
    private void launchNewTask(Map.Entry<String, Integer> requirement, Offer offer, SchedulerDriver driver) {
        TaskInfo task = TaskInfoFactory.buildTask(requirement, offer, configuration);
        configuration.registerTask(requirement.getKey(), task.getTaskId());
        Filters filters = Filters.newBuilder().setRefuseSeconds(1).build();
        driver.launchTasks(Arrays.asList(offer.getId()), Arrays.asList(task), filters);
    }

    @Override
    public void registered(SchedulerDriver schedulerDriver, FrameworkID frameworkID, MasterInfo masterInfo) {
        LOGGER.info("Registered at: master={}:{}, framework={}", masterInfo.getIp(), masterInfo.getPort(), frameworkID);
        configuration.getState().setFrameworkId(frameworkID);
    }

    @Override
    public void reregistered(SchedulerDriver schedulerDriver, MasterInfo masterInfo) {
        LOGGER.info("Reregistered.");
    }

    // TODO Excess tasks won't be killed if the master has nothing to offer. We might want to move the killing of tasks to a separate method that can be called from the service directly
    @Override
    public void resourceOffers(SchedulerDriver schedulerDriver, List<Offer> offers) {
        LOGGER.info("Offered {} offers", offers.size());

        //Check which offers are acceptable
        List<Offer> acceptableOffers = new ArrayList<>();
        for (Offer offer : offers) {
            if (offerIsAcceptable(offer))
                acceptableOffers.add(offer);
            else
                schedulerDriver.declineOffer(offer.getId());
        }
        LOGGER.info("A total of {} offers were deemed acceptable.", acceptableOffers.size());

        //Spin up/kill off tasks as necessary
        for (Map.Entry<String, Integer> requirement : configuration.getRequirementDeltaMap().entrySet()) {

            int delta = requirement.getValue();
            String esLink = requirement.getKey();

            if (delta > 0) {
                LOGGER.info("ElasticSearch {} is missing {} tasks. Attempting to spin up required tasks.", esLink, delta);
                while (delta > 0) {
                    if (acceptableOffers.isEmpty()) break;
                    Offer pickedOffer = acceptableOffers.get(0);
                    launchNewTask(requirement, pickedOffer, schedulerDriver);
                    acceptableOffers.remove(pickedOffer);
                    delta--;
                }
            } else if (delta < 0) {
                LOGGER.info("ElasticSearch {} has an excess of {} tasks. Attempting to kill off excess tasks.", esLink, delta);
                while (delta < 0) {
                    TaskID excessTask = configuration.getYoungestTask(esLink);
                    if (excessTask == null) break;
                    else {
                        configuration.unregisterTask(excessTask);
                        schedulerDriver.killTask(excessTask);
                        LOGGER.info("Killed task {}.", excessTask.getValue());
                        delta++;
                    }

                }
            }
        }

        // DCOS-05 Scheduler MUST decline offers it doesn’t need.
        for (Offer remainingOffer : acceptableOffers) {
            schedulerDriver.declineOffer(remainingOffer.getId());
        }
    }

    @Override
    public void offerRescinded(SchedulerDriver schedulerDriver, OfferID offerID) {
        LOGGER.info("Offer {} rescinded.", offerID.getValue());
    }

    @Override
    public void statusUpdate(SchedulerDriver driver, TaskStatus taskStatus) {
        TaskID taskId = taskStatus.getTaskId();
        LOGGER.info("Task {} is in state {}", taskId.getValue(), taskStatus.getState());

        switch (taskStatus.getState()) {
            case TASK_FAILED:
            case TASK_FINISHED:
                LOGGER.info("Unregistering task {} due to state: {}", taskId.getValue(), taskStatus.getState());
                configuration.unregisterTask(taskId);
                break;
        }
    }

    @Override
    public void frameworkMessage(SchedulerDriver schedulerDriver, ExecutorID executorID, SlaveID slaveID, byte[] bytes) {
        LOGGER.info("Hit frameworkMessage()");
    }

    @Override
    public void disconnected(SchedulerDriver schedulerDriver) {
        LOGGER.info("Disconnected.");
    }

    @Override
    public void slaveLost(SchedulerDriver schedulerDriver, SlaveID slaveID) {
        LOGGER.info("Lost slave {}", slaveID.getValue());
    }

    @Override
    public void executorLost(SchedulerDriver schedulerDriver, ExecutorID executorID, SlaveID slaveID, int i) {
        LOGGER.info("Lost executor {}", executorID.getValue());
    }

    @Override
    public void error(SchedulerDriver schedulerDriver, String s) {
        LOGGER.error("ERROR: {}", s);
    }
}