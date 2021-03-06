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

import org.moqui.util.ContextUtil;
import org.moqui.workflow.util.WorkflowEventType;
import org.moqui.workflow.util.WorkflowUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.json.JSONObject;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;
import org.moqui.service.ServiceException;
import org.moqui.service.ServiceFacade;

import java.util.Map;

/**
 * Workflow activity used to execute a Moqui service.
 */
public class WorkflowServiceActivity extends AbstractWorkflowActivity {

    /**
     * Creates a new activity.
     *
     * @param activity Activity entity
     */
    public WorkflowServiceActivity(EntityValue activity) {
        this.activity = activity;
    }

    @Override
    public boolean execute(ExecutionContext ec, EntityValue instance) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ServiceFacade sf = ec.getService();

        // get attributes
        String activityId = activity.getString("activityId");
        String activityTypeEnumId = activity.getString("activityTypeEnumId");
        String activityTypeDescription = activity.getString("activityTypeDescription");
        String instanceId = instance.getString("instanceId");

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Executing %s activity (%s) ...", logId, activityTypeEnumId, activityId));

        // get service name
        JSONObject nodeData = new JSONObject(activity.getString("nodeData"));
        String serviceName = nodeData.has("serviceName") ? nodeData.getString("serviceName").trim() : null;
        String parameters = nodeData.has("parameters") ? nodeData.getString("parameters") : null;

        // execute service
        if(StringUtils.isNotBlank(serviceName)) {
            logger.debug(String.format("[%s] Executing service: %s", logId, serviceName));
            try {
                Map<String, Object> response = sf.sync()
                        .name(serviceName)
                        .parameter("instance", instance)
                        .parameter("parameters", parameters)
                        .call();
                logger.debug(String.format("[%s] Service executed successfully", logId));
                logger.debug(String.format("[%s] Got response: %s", logId, response.toString()));
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
