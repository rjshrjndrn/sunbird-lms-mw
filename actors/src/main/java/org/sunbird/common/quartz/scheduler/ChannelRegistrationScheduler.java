package org.sunbird.common.quartz.scheduler;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.LoggerEnum;
import org.sunbird.common.models.util.ProjectLogger;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.request.Request;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.learner.util.ActorUtil;
import org.sunbird.learner.util.TelemetryUtil;
import org.sunbird.learner.util.Util;
import org.sunbird.telemetry.util.lmaxdisruptor.TelemetryEvents;

public class ChannelRegistrationScheduler implements Job {

  @Override
  public void execute(JobExecutionContext ctx) throws JobExecutionException {
    ProjectLogger.log("Running channel registration Scheduler Job at: "
        + Calendar.getInstance().getTime() + " triggered by: " + ctx.getJobDetail().toString(),
        LoggerEnum.INFO.name());

    Util.initializeContextForSchedulerJob(JsonKey.SYSTEM, ctx.getFireInstanceId(), JsonKey.SCHEDULER_JOB);
    Map<String, Object> logInfo =
        genarateLogInfo(JsonKey.SYSTEM, ctx.getJobDetail().getDescription());
    Request request = new Request();
    request.setOperation(ActorOperations.REG_CHANNEL.getValue());
    CassandraOperation cassandraOperation = ServiceFactory.getInstance();
    Response response = cassandraOperation.getRecordById(JsonKey.SUNBIRD,
        JsonKey.SYSTEM_SETTINGS_DB, JsonKey.CHANNEL_REG_STATUS_ID);
    List<Map<String, Object>> responseList =
        (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
    if (null != responseList && !responseList.isEmpty()) {
      Map<String, Object> resultMap = responseList.get(0);
      ProjectLogger.log("value for CHANNEL_REG_STATUS_ID (003) from SYSTEM_SETTINGS_DB is : "
          + (String) resultMap.get(JsonKey.VALUE));
      if (ProjectUtil.isStringNullOREmpty((String) resultMap.get(JsonKey.VALUE))
          && !Boolean.parseBoolean((String) resultMap.get(JsonKey.VALUE))) {
        ProjectLogger.log(
            "calling ChannelRegistrationActor from ChannelRegistrationScheduler execute method.");
        ActorUtil.tell(request);
      }
    } else {
      ProjectLogger
          .log("calling ChannelRegistrationActor from ChannelRegistrationScheduler execute method, "
              + "entry for CHANNEL_REG_STATUS_ID (003) is null.");
      ActorUtil.tell(request);
    }
    TelemetryUtil.telemetryProcessingCall(logInfo, null, null, TelemetryEvents.LOG.getName());
  }

  private Map<String, Object> genarateLogInfo(String logType, String message) {

    Map<String, Object> info = new HashMap<>();
    info.put(JsonKey.LOG_TYPE, logType);
    long startTime = System.currentTimeMillis();
    info.put(JsonKey.START_TIME, startTime);
    info.put(JsonKey.MESSAGE, message);
    info.put(JsonKey.LOG_LEVEL, JsonKey.INFO);

    return info;
  }

}
