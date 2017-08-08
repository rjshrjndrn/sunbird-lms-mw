package org.sunbird.learner.actors;

import static org.sunbird.common.models.util.ProjectUtil.isNotNull;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.concurrent.CopyOnWriteArrayList;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.cassandraimpl.CassandraOperationImpl;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.ProjectLogger;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.models.util.datasecurity.OneWayHashing;
import org.sunbird.common.request.Request;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.learner.util.Util;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedAbstractActor;

/**
 * This actor to handle learner's state update operation .
 *
 * @author Manzarul
 * @author Arvind
 */
public class LearnerStateUpdateActor extends UntypedAbstractActor {

  private final String CONTENT_STATE_INFO = "contentStateInfo";
  private SimpleDateFormat sdf = ProjectUtil.format;
  private CassandraOperation cassandraOperation = new CassandraOperationImpl();
  private ActorRef utilityActorRef;

  public LearnerStateUpdateActor() {
    utilityActorRef = getContext().actorOf(Props.create(UtilityActor.class), "UtilityActor");
  }

  /**
   * Receives the actor message and perform the add content operation .
   *
   * @param message Request
   */
  @SuppressWarnings("unchecked")
  @Override
  public void onReceive(Object message) throws Throwable {
    if (message instanceof Request) {
      try {
        ProjectLogger.log("LearnerStateUpdateActor onReceive called");
        Request actorMessage = (Request) message;
        Response response = new Response();
        if (actorMessage.getOperation().equalsIgnoreCase(ActorOperations.ADD_CONTENT.getValue())) {
          Util.DbInfo dbInfo = Util.dbInfoMap.get(JsonKey.LEARNER_CONTENT_DB);
          String userId = (String) actorMessage.getRequest().get(JsonKey.USER_ID);
          List<Map<String, Object>> requestedcontentList = (List<Map<String, Object>>) actorMessage
              .getRequest().get(JsonKey.CONTENTS);
          CopyOnWriteArrayList<Map<String, Object>> contentList = new CopyOnWriteArrayList<Map<String, Object>>(requestedcontentList);
          actorMessage
              .getRequest().put(JsonKey.CONTENTS , contentList);
          // map to hold the status of requested state of contents
          Map<String, Integer> contentStatusHolder = new HashMap<String, Integer>();

          if (!(contentList.isEmpty())) {
            for (Map<String, Object> map : contentList) {
              //replace the course id (equivalent to Ekstep content id) with One way hashing of userId#courseId , bcoz in cassndra we are saving course id as userId#courseId
              map.putIfAbsent(JsonKey.COURSE_ID, JsonKey.NOT_AVAILABLE);
              preOperation(map, userId, contentStatusHolder);
              map.put(JsonKey.USER_ID, userId);
              map.put(JsonKey.DATE_TIME, new Timestamp(new Date().getTime()));

              try {
                cassandraOperation.upsertRecord(dbInfo.getKeySpace(), dbInfo.getTableName(), map);
                response.getResult().put((String) map.get(JsonKey.CONTENT_ID), JsonKey.SUCCESS);
              } catch (Exception ex) {
                response.getResult().put((String) map.get(JsonKey.CONTENT_ID), JsonKey.FAILED);
                contentList.remove(map);
              }
            }
          }
          sender().tell(response, self());
          //call to update the corresponding course
          actorMessage.getRequest().put(this.CONTENT_STATE_INFO, contentStatusHolder);
          utilityActorRef.tell(actorMessage, ActorRef.noSender());
        } else {
          ProjectLogger.log("UNSUPPORTED OPERATION");
          ProjectCommonException exception = new ProjectCommonException(
              ResponseCode.invalidOperationName.getErrorCode(),
              ResponseCode.invalidOperationName.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode());
          sender().tell(exception, ActorRef.noSender());
        }
      } catch (Exception ex) {
        ProjectLogger.log(ex.getMessage(), ex);
        sender().tell(ex, ActorRef.noSender());
      }
    } else {
      ProjectLogger.log("UNSUPPORTED MESSAGE");
      ProjectCommonException exception = new ProjectCommonException(
          ResponseCode.invalidRequestData.getErrorCode(),
          ResponseCode.invalidRequestData.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());
      sender().tell(exception, ActorRef.noSender());
    }
  }

  /**
   * Method te perform the per operation on contents like setting the status , last completed and
   * access time etc.
   */
  @SuppressWarnings("unchecked")
  private void preOperation(Map<String, Object> req, String userId,
      Map<String, Integer> contentStateHolder) throws ParseException {

    Util.DbInfo dbInfo = Util.dbInfoMap.get(JsonKey.LEARNER_CONTENT_DB);
    req.put(JsonKey.ID, generatePrimaryKey(req, userId));
    contentStateHolder
        .put((String) req.get(JsonKey.ID), ((BigInteger) req.get(JsonKey.STATUS)).intValue());
    Response response = cassandraOperation
        .getRecordById(dbInfo.getKeySpace(), dbInfo.getTableName(), (String) req.get(JsonKey.ID));

    List<Map<String, Object>> resultList = (List<Map<String, Object>>) response.getResult()
        .get(JsonKey.RESPONSE);

    if (!(resultList.isEmpty())) {
      Map<String, Object> result = resultList.get(0);
      int currentStatus = (int) result.get(JsonKey.STATUS);
      int requestedStatus = ((BigInteger) req.get(JsonKey.STATUS)).intValue();

      Integer currentProgressStatus = 0;
      if (isNotNull(result.get(JsonKey.CONTENT_PROGRESS))) {
        currentProgressStatus = (Integer) result.get(JsonKey.CONTENT_PROGRESS);
      }
      if (isNotNull(req.get(JsonKey.CONTENT_PROGRESS))) {
        Integer requestedProgressStatus = ((BigInteger) req.get(JsonKey.CONTENT_PROGRESS))
            .intValue();
        if (requestedProgressStatus > currentProgressStatus) {
          req.put(JsonKey.CONTENT_PROGRESS, requestedProgressStatus);
        } else {
          req.put(JsonKey.CONTENT_PROGRESS, currentProgressStatus);
        }
      } else {
        req.put(JsonKey.CONTENT_PROGRESS, currentProgressStatus);
      }

      Date accessTime = parseDate(result.get(JsonKey.LAST_ACCESS_TIME), sdf);
      Date requestAccessTime = parseDate(req.get(JsonKey.LAST_ACCESS_TIME), sdf);

      Date completedDate = parseDate(result.get(JsonKey.LAST_COMPLETED_TIME), sdf);
      Date requestCompletedTime = parseDate(req.get(JsonKey.LAST_COMPLETED_TIME), sdf);

      int completedCount;
      if (!(isNullCheck(result.get(JsonKey.COMPLETED_COUNT)))) {
        completedCount = (int) result.get(JsonKey.COMPLETED_COUNT);
      } else {
        completedCount = 0;
      }
      int viewCount;
      if (!(isNullCheck(result.get(JsonKey.VIEW_COUNT)))) {
        viewCount = (int) result.get(JsonKey.VIEW_COUNT);
      } else {
        viewCount = 0;
      }

      if (requestedStatus >= currentStatus) {
        req.put(JsonKey.STATUS, requestedStatus);
        if (requestedStatus == 2) {
          req.put(JsonKey.COMPLETED_COUNT, completedCount + 1);
          req.put(JsonKey.LAST_COMPLETED_TIME, compareTime(completedDate, requestCompletedTime));
        } else {
          req.put(JsonKey.COMPLETED_COUNT, completedCount);
        }
        req.put(JsonKey.VIEW_COUNT, viewCount + 1);
        req.put(JsonKey.LAST_ACCESS_TIME, compareTime(accessTime, requestAccessTime));
        req.put(JsonKey.LAST_UPDATED_TIME, ProjectUtil.getFormattedDate());

      } else {
        req.put(JsonKey.STATUS, currentStatus);
        req.put(JsonKey.VIEW_COUNT, viewCount + 1);
        req.put(JsonKey.LAST_ACCESS_TIME, compareTime(accessTime, requestAccessTime));
        req.put(JsonKey.LAST_UPDATED_TIME, ProjectUtil.getFormattedDate());
        req.put(JsonKey.COMPLETED_COUNT, completedCount);
      }

    } else {
      // IT IS NEW CONTENT SIMPLY ADD IT
      Date requestCompletedTime = parseDate(req.get(JsonKey.LAST_COMPLETED_TIME), sdf);
      if (null != req.get(JsonKey.STATUS)) {
        int requestedStatus = ((BigInteger) req.get(JsonKey.STATUS)).intValue();
        req.put(JsonKey.STATUS, requestedStatus);
        if (requestedStatus == 2) {
          req.put(JsonKey.COMPLETED_COUNT, 1);
          req.put(JsonKey.LAST_COMPLETED_TIME, compareTime(null, requestCompletedTime));
          req.put(JsonKey.COMPLETED_COUNT, 1);
        }else{
          req.put(JsonKey.COMPLETED_COUNT, 0);
        }

      } else {
        req.put(JsonKey.STATUS, ProjectUtil.ProgressStatus.NOT_STARTED.getValue());
        req.put(JsonKey.COMPLETED_COUNT, 0);
      }

      int progressStatus = 0;
      if (isNotNull(req.get(JsonKey.CONTENT_PROGRESS))) {
        progressStatus = ((BigInteger) req.get(JsonKey.CONTENT_PROGRESS)).intValue();
      }
      req.put(JsonKey.CONTENT_PROGRESS, progressStatus);

      req.put(JsonKey.VIEW_COUNT, 1);
      Date requestAccessTime = parseDate(req.get(JsonKey.LAST_ACCESS_TIME), sdf);

      req.put(JsonKey.LAST_UPDATED_TIME, ProjectUtil.getFormattedDate());

      if (requestAccessTime != null) {
        req.put(JsonKey.LAST_ACCESS_TIME, (String) req.get(JsonKey.LAST_ACCESS_TIME));
      } else {
        req.put(JsonKey.LAST_ACCESS_TIME, ProjectUtil.getFormattedDate());
      }

    }
  }

  private Date parseDate(Object obj, SimpleDateFormat formatter) throws ParseException {
    if (null == obj || ((String) obj).equalsIgnoreCase(JsonKey.NULL)) {
      return null;
    }
    Date date;
    try {
      date = formatter.parse((String) obj);
    } catch (ParseException ex) {
      ex.printStackTrace();
      throw new ProjectCommonException(
          ResponseCode.invalidDateFormat.getErrorCode(),
          ResponseCode.invalidDateFormat.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());

    }
    return date;
  }

  private String compareTime(Date currentValue, Date requestedValue) {
    if (currentValue == null && requestedValue == null) {
      return ProjectUtil.getFormattedDate();
    } else if (currentValue == null) {
      return sdf.format(requestedValue);
    } else if (null == requestedValue) {
      return sdf.format(currentValue);
    }
    return (requestedValue.after(currentValue) ? sdf.format(requestedValue)
        : sdf.format(currentValue));
  }

  private String generatePrimaryKey(Map<String, Object> req, String userId) {
    String contentId = (String) req.get(JsonKey.CONTENT_ID);
    String courseId = (String) req.get(JsonKey.COURSE_ID);
    String batchId = (String) req.get(JsonKey.BATCH_ID);
    String key = userId + JsonKey.PRIMARY_KEY_DELIMETER + contentId + JsonKey.PRIMARY_KEY_DELIMETER
        + courseId+JsonKey.PRIMARY_KEY_DELIMETER+batchId;
    return OneWayHashing.encryptVal(key);
  }

  private boolean isNullCheck(Object obj) {
    return null == obj;
  }
}
