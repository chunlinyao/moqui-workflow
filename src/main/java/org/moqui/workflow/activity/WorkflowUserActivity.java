/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.workflow.activity;

import java.math.*;
import java.sql.*;
import java.util.Date;
import java.util.*;

import org.apache.commons.lang3.*;
import org.apache.commons.lang3.time.*;
import org.json.*;
import org.moqui.context.*;
import org.moqui.entity.*;
import org.moqui.service.*;
import org.moqui.util.*;
import org.moqui.workflow.util.*;

/**
 * Workflow activity used to create different types of user tasks.
 */
public class WorkflowUserActivity extends AbstractWorkflowActivity {

    /**
     * Creates a new activity.
     *
     * @param  activity Activity entity
     */
    public WorkflowUserActivity(EntityValue  activity) {
        this. activity =  activity;
    }

    @Override
    public boolean execute(ExecutionContext ec, EntityValue instance) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        EntityFacade ef = ec.getEntity();
        ServiceFacade sf = ec.getService();

        // get attributes
        String activityId = activity.getString("activityId");
        String activityTypeEnumId = activity.getString("activityTypeEnumId");
        String activityTypeDescription = activity.getString("activityTypeDescription");
        String instanceId = instance.getString("instanceId");
        String inputUserId = instance.getString("inputUserId");

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Executing %s activity (%s) ...", logId, activityTypeEnumId, activityId));

        // get attributes
        JSONObject nodeData = new JSONObject(activity.getString("nodeData"));
        WorkflowTaskType taskType = nodeData.has("taskTypeEnumId") ? EnumUtils.getEnum(WorkflowTaskType.class, nodeData.getString("taskTypeEnumId")) : null;
        JSONArray crowds = nodeData.has("crowds") ? nodeData.getJSONArray("crowds") : new JSONArray();
        String variableId = nodeData.has("variableId") ? nodeData.getString("variableId") : null;
        String summary = nodeData.has("summary") ? nodeData.getString("summary") : null;
        String description = nodeData.has("description") ? nodeData.getString("description") : null;
        int timeoutInterval = nodeData.has("timeoutInterval") ? nodeData.getInt("timeoutInterval") : 0;
        String timeoutUomId = nodeData.has("timeoutUomId") ? nodeData.getString("timeoutUomId") : null;
        boolean autoStart = nodeData.has("autoStart") ? nodeData.getBoolean("autoStart") : false;

        // get the user accounts
        ArrayList<EntityValue> userAccounts = new ArrayList<>();
        for (int i=0; i<crowds.length(); i++) {
            JSONObject crowd = crowds.getJSONObject(i);
            WorkflowCrowdType crowdType = crowd.has("crowdTypeEnumId") ? EnumUtils.getEnum(WorkflowCrowdType.class, crowd.getString("crowdTypeEnumId")) : null;
            String userId = crowd.has("userId") ? crowd.getString("userId") : null;
            String userGroupId = crowd.has("userGroupId") ? crowd.getString("userGroupId") : null;
            String serviceName = crowd.has("serviceName") ? crowd.getString("serviceName") : null;
            String parameters = crowd.has("serviceParameters") ? crowd.getString("serviceParameters") : null;

            if (crowdType == WorkflowCrowdType.WF_CROWD_USER && StringUtils.isNotBlank(userId)) {
                EntityValue userAccount = ef.find("moqui.security.UserAccount")
                        .condition("userId", userId)
                        .one();
                if (userAccount!=null) {
                    userAccounts.add(userAccount);
                }
            } else if (crowdType == WorkflowCrowdType.WF_CROWD_USER_GROUP && StringUtils.isNotBlank(userGroupId)) {
                EntityList groupMembers = ef.find("moqui.security.UserGroupMember")
                        .condition("userGroupId", userGroupId)
                        .conditionDate("fromDate", "thruDate", TimestampUtil.now())
                        .list();
                for (EntityValue groupMember : groupMembers) {
                    EntityValue userAccount = ef.find("moqui.security.UserAccount")
                            .condition("userId", groupMember.getString("userId"))
                            .one();
                    if (userAccount != null) {
                        userAccounts.add(userAccount);
                    }
                }
            } else if (crowdType == WorkflowCrowdType.WF_CROWD_SERVICE && StringUtils.isNotBlank(serviceName)) {
                logger.debug(String.format("[%s] Executing service: %s", logId, serviceName));
                try {
                    ServiceCallSync serviceCall = sf.sync().name(serviceName)
                            .parameter("instance", instance.cloneValue())
                            .parameter("serviceParameters", parameters);
                    Map<String, Object> response = serviceCall.call();
                    logger.debug(String.format("[%s] Service executed successfully", logId));
                    logger.debug(String.format("[%s] Got response: %s", logId, response.toString()));
                    if (response.containsKey("userAccounts")) {
                        @SuppressWarnings("unchecked")
                        boolean b = userAccounts.addAll((Collection<? extends EntityValue>) response.get("userAccounts"));
                    }
                } catch (ServiceException e) {
                    stopWatch.stop();
                    logger.error(String.format("[%s] An error occurred while running service: %s", logId, e.getMessage()), e);
                    WorkflowUtil.createWorkflowEvent(
                            ec,
                            instanceId,
                            WorkflowEventType.WF_EVENT_ACTIVITY,
                            String.format("Failed to execute %s activity (%s) due to error: %s", activityTypeDescription, activityId, e.getMessage()),
                            true
                    );
                    return false;
                }
            } else if (crowdType == WorkflowCrowdType.WF_CROWD_INITIATOR) {
                EntityValue userAccount = ef.find("moqui.security.UserAccount")
                        .condition("userId", inputUserId)
                        .one();
                if (userAccount != null) {
                    userAccounts.add(userAccount);
                }
            }
        }

        // create tasks
        for(EntityValue userAccount : userAccounts) {
            String userId = userAccount.getString("userId");
            Map<String, Object> resp = sf.sync().name("create#moqui.workflow.WorkflowInstanceTask")
                    .parameter("instanceId", instanceId)
                    .parameter("activityId", activityId)
                    .parameter("assignedUserId", userId)
                    .parameter("taskTypeEnumId", taskType)
                    .parameter("variableId", variableId)
                    .parameter("statusId", autoStart ? WorkflowTaskStatus.WF_TASK_STAT_PROGRESS: WorkflowTaskStatus.WF_TASK_STAT_PEND)
                    .parameter("summary", summary)
                    .parameter("description", description)
                    .call();
            String taskId = (String) resp.get("taskId");
            logger.debug(String.format("[%s] Added task %s for user %s", logId, taskId, userId));
        }

        // set the instance timeout
        if(timeoutInterval > 0 && StringUtils.isNotBlank(timeoutUomId)) {
            Map<String, Object> convertResp = sf.sync().name("org.moqui.impl.BasicServices.convert#Uom")
                    .parameter("uomId", timeoutUomId)
                    .parameter("toUomId", TimeFrequency.TF_min.name())
                    .parameter("amount", timeoutInterval)
                    .call();
            int timeoutIntervalMins = ((BigDecimal)convertResp.get("convertedAmount")).intValue();

            Date timeoutDate = DateUtils.addMinutes(new Date(), timeoutIntervalMins);
            Timestamp timeoutDateTs = new Timestamp(timeoutDate.getTime());
            sf.sync().name("update#moqui.workflow.WorkflowInstance")
                    .parameter("instanceId", instanceId)
                    .parameter("timeoutDate", timeoutDateTs)
                    .parameter("lastUpdateDate", TimestampUtil.now())
                    .call();
        }

        // create event
        WorkflowUtil.createWorkflowEvent(
                ec,
                instanceId,
                WorkflowEventType.WF_EVENT_ACTIVITY,
                String.format("Executed %s activity (%s)", activityTypeDescription, activityId),
                false
        );

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] %s activity (%s) executed in %d milliseconds", logId, activityTypeEnumId, activityId, stopWatch.getTime()));

        // activity executed successfully
        return true;
    }
}
