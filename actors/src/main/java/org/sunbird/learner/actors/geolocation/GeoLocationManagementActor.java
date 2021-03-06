package org.sunbird.learner.actors.geolocation;

import akka.actor.UntypedAbstractActor;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.LoggerEnum;
import org.sunbird.common.models.util.ProjectLogger;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.models.util.fcm.Notification;
import org.sunbird.common.request.ExecutionContext;
import org.sunbird.common.request.Request;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.learner.util.ActorUtil;
import org.sunbird.learner.util.TelemetryUtil;
import org.sunbird.learner.util.Util;

/**
 * Class for providing Geo Location for Organisation Created by arvind on 31/10/17.
 */
public class GeoLocationManagementActor extends UntypedAbstractActor {

  private Util.DbInfo geoLocationDbInfo = Util.dbInfoMap.get(JsonKey.GEO_LOCATION_DB);
  private CassandraOperation cassandraOperation = ServiceFactory.getInstance();
  private Util.DbInfo orgDbInfo = Util.dbInfoMap.get(JsonKey.ORG_DB);
  private SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");

  @Override
  public void onReceive(Object message) throws Throwable {
    if (message instanceof Request) {
      try {
        ProjectLogger.log("GeoLocationManagementActor-onReceive called");
        Request actorMessage = (Request) message;
        ExecutionContext.setRequestId(actorMessage.getRequestId());
        Util.initializeContext(actorMessage, JsonKey.LOCATION);
        if (actorMessage.getOperation()
            .equalsIgnoreCase(ActorOperations.CREATE_GEO_LOCATION.getValue())) {
          createGeoLocation(actorMessage);
        } else if (actorMessage.getOperation()
            .equalsIgnoreCase(ActorOperations.GET_GEO_LOCATION.getValue())) {
          getGeoLocation(actorMessage);
        } else if (actorMessage.getOperation()
            .equalsIgnoreCase(ActorOperations.UPDATE_GEO_LOCATION.getValue())) {
          updateGeoLocation(actorMessage);
        } else if (actorMessage.getOperation()
            .equalsIgnoreCase(ActorOperations.DELETE_GEO_LOCATION.getValue())) {
          deleteGeoLocation(actorMessage);
        } else if (actorMessage.getOperation()
            .equalsIgnoreCase(ActorOperations.SEND_NOTIFICATION.getValue())) {
          sendNotification(actorMessage);
        } else if (actorMessage.getOperation()
            .equalsIgnoreCase(ActorOperations.GET_USER_COUNT.getValue())) {
          getUserCount(actorMessage);
        } else {
          ProjectLogger.log("UNSUPPORTED OPERATION", LoggerEnum.INFO.name());
          ProjectCommonException exception =
              new ProjectCommonException(ResponseCode.invalidOperationName.getErrorCode(),
                  ResponseCode.invalidOperationName.getErrorMessage(),
                  ResponseCode.CLIENT_ERROR.getResponseCode());
          sender().tell(exception, self());
        }
      } catch (Exception ex) {
        ProjectLogger.log(ex.getMessage(), ex);
        sender().tell(ex, self());
      }
    } else {
      ProjectLogger.log("UNSUPPORTED MESSAGE");
      ProjectCommonException exception =
          new ProjectCommonException(ResponseCode.invalidRequestData.getErrorCode(),
              ResponseCode.invalidRequestData.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode());
      sender().tell(exception, self());
    }
  }


  private void getUserCount(Request actorMessage) {
    ProjectLogger.log("sendnotification actor method called.");
    List<Object> locationIds = (List<Object>) actorMessage.getRequest().get(JsonKey.LOCATION_IDS);
    List<Map<String, Object>> result = new ArrayList<>();
    List<String> dbIdList = new ArrayList<>();
    Map<String, Object> responseMap = null;

    Response response = cassandraOperation.getRecordsByProperty(geoLocationDbInfo.getKeySpace(),
        geoLocationDbInfo.getTableName(), JsonKey.ID, locationIds);
    List<Map<String, Object>> list = (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
    for (Map<String, Object> map : list) {
      responseMap = new HashMap<>();
      responseMap.put(JsonKey.ID, map.get(JsonKey.ID));
      responseMap.put(JsonKey.USER_COUNT,
          ((map.get(JsonKey.USER_COUNT) == null) ? 0 : map.get(JsonKey.USER_COUNT)));
      result.add(responseMap);
      dbIdList.add((String) map.get(JsonKey.ID));
    }
    // For Invalid Location Id
    for (Object str : locationIds) {
      if (!dbIdList.contains((String) str)) {
        responseMap = new HashMap<>();
        responseMap.put(JsonKey.ID, str);
        responseMap.put(JsonKey.USER_COUNT, 0);
        result.add(responseMap);
      }
    }
    response = new Response();
    response.getResult().put(JsonKey.LOCATIONS, result);
    sender().tell(response, self());
    // Update user count in background
    actorMessage.setOperation(ActorOperations.UPDATE_USER_COUNT_TO_LOCATIONID.getValue());
    actorMessage.getRequest().put(JsonKey.OPERATION, "GeoLocationManagementActor");
    ActorUtil.tell(actorMessage);
  }


  private void sendNotification(Request actorMessage) {
    ProjectLogger.log("sendnotification actor method called.");
    String topic = (String) actorMessage.getRequest().get(JsonKey.TO);
    // Topic name is same as Location id in current system.
    // if logic is change then we need to update the matching logic as well
    Response response = cassandraOperation.getRecordById(geoLocationDbInfo.getKeySpace(),
        geoLocationDbInfo.getTableName(), topic);
    List<Map<String, Object>> list = (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
    if (list.isEmpty()) {
      // throw exception that invalid topic ...
      throw new ProjectCommonException(ResponseCode.invalidTopic.getErrorCode(),
          ResponseCode.invalidTopic.getErrorMessage(), ResponseCode.CLIENT_ERROR.getResponseCode());
    }
    Map<String, Object> notificationData = (Map) actorMessage.getRequest().get(JsonKey.DATA);
    String message = Notification.sendNotification(topic, notificationData, Notification.FCM_URL);
    ProjectLogger.log("FCM message from Google ==" + message);
    response = new Response();
    if (JsonKey.FAILURE.equalsIgnoreCase(message)) {
      response.getResult().put(JsonKey.RESPONSE, JsonKey.FAILURE);
    } else {
      response.getResult().put(JsonKey.RESPONSE, JsonKey.SUCCESS);
      response.getResult().put(JsonKey.ID, message);
    }
    sender().tell(response, self());
  }


  /**
   * Delete geo location on basis of location id.
   * 
   * @param actorMessage
   */
  private void deleteGeoLocation(Request actorMessage) {

    ProjectLogger.log("GeoLocationManagementActor-updateGeoLocation called");

    // object of telemetry event...
    Map<String, Object> targetObject = null;
    List<Map<String, Object>> correlatedObject = new ArrayList<>();

    String locationId = (String) actorMessage.getRequest().get(JsonKey.LOCATION_ID);
    Response finalResponse = new Response();

    if (ProjectUtil.isStringNullOREmpty(locationId)) {
      throw new ProjectCommonException(ResponseCode.invalidRequestData.getErrorCode(),
          ResponseCode.invalidRequestData.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());
    }

    cassandraOperation.deleteRecord(geoLocationDbInfo.getKeySpace(),
        geoLocationDbInfo.getTableName(), locationId);
    finalResponse.getResult().put(JsonKey.RESPONSE, JsonKey.SUCCESS);
    sender().tell(finalResponse, self());

    targetObject =
        TelemetryUtil.generateTargetObject(locationId, JsonKey.LOCATION, JsonKey.DELETE, null);
    TelemetryUtil.telemetryProcessingCall(actorMessage.getRequest(), targetObject,
        correlatedObject);

  }

  /**
   * Update geo location on basis of locationId , only location type and
   * 
   * @param actorMessage
   */
  private void updateGeoLocation(Request actorMessage) {

    ProjectLogger.log("GeoLocationManagementActor-updateGeoLocation called");

    // object of telemetry event...
    Map<String, Object> targetObject = null;
    List<Map<String, Object>> correlatedObject = new ArrayList<>();

    String requestedBy = (String) actorMessage.getRequest().get(JsonKey.REQUESTED_BY);
    String locationId = (String) actorMessage.getRequest().get(JsonKey.LOCATION_ID);
    String type = (String) actorMessage.getRequest().get(JsonKey.TYPE);
    String location = (String) actorMessage.getRequest().get(JsonKey.LOCATION);
    Response finalResponse = new Response();

    if (ProjectUtil.isStringNullOREmpty(locationId)) {
      throw new ProjectCommonException(ResponseCode.invalidRequestData.getErrorCode(),
          ResponseCode.invalidRequestData.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());
    }

    Response response1 = cassandraOperation.getRecordById(geoLocationDbInfo.getKeySpace(),
        geoLocationDbInfo.getTableName(), locationId);
    List<Map<String, Object>> list = (List<Map<String, Object>>) response1.get(JsonKey.RESPONSE);
    if (list.isEmpty()) {
      // throw exception that invalid location id ...
      throw new ProjectCommonException(ResponseCode.invalidLocationId.getErrorCode(),
          ResponseCode.invalidLocationId.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());
    }
    Map<String, Object> dbResult = list.get(0);

    Map<String, Object> dbMap = new HashMap<>();
    if (!ProjectUtil.isStringNullOREmpty(type)) {
      dbMap.put(JsonKey.TYPE, type);
    }
    if (!ProjectUtil.isStringNullOREmpty(location)) {
      dbMap.put(JsonKey.LOCATION, location);
    }

    dbMap.put(JsonKey.UPDATED_BY, requestedBy);
    dbMap.put(JsonKey.UPDATED_DATE, format.format(new Date()));

    dbMap.put(JsonKey.ID, dbResult.get(JsonKey.ID));
    cassandraOperation.updateRecord(geoLocationDbInfo.getKeySpace(),
        geoLocationDbInfo.getTableName(), dbMap);

    finalResponse.put(JsonKey.RESPONSE, JsonKey.SUCCESS);
    sender().tell(finalResponse, self());

    targetObject =
        TelemetryUtil.generateTargetObject(locationId, JsonKey.LOCATION, JsonKey.UPDATE, null);
    TelemetryUtil.telemetryProcessingCall(actorMessage.getRequest(), targetObject,
        correlatedObject);

  }

  /**
   * Get geo location on basis of type and id . type should be organisation or location .
   * 
   * @param actorMessage
   */
  private void getGeoLocation(Request actorMessage) {

    ProjectLogger.log("GeoLocationManagementActor-getGeoLocation called");
    String id = (String) actorMessage.getRequest().get(JsonKey.ID);
    String type = (String) actorMessage.getRequest().get(JsonKey.TYPE);
    Response finalResponse = new Response();

    if (ProjectUtil.isStringNullOREmpty(id) || ProjectUtil.isStringNullOREmpty(type)) {
      throw new ProjectCommonException(ResponseCode.invalidRequestData.getErrorCode(),
          ResponseCode.invalidRequestData.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());
    }

    if (type.equalsIgnoreCase(JsonKey.ORGANISATION)) {

      Response response1 = cassandraOperation.getRecordsByProperty(geoLocationDbInfo.getKeySpace(),
          geoLocationDbInfo.getTableName(), JsonKey.ROOT_ORG_ID, id);
      List<Map<String, Object>> list = (List<Map<String, Object>>) response1.get(JsonKey.RESPONSE);

      finalResponse.put(JsonKey.RESPONSE, list);

      sender().tell(finalResponse, self());
      return;

    } else if (type.equalsIgnoreCase(JsonKey.LOCATION)) {

      Response response1 = cassandraOperation.getRecordById(geoLocationDbInfo.getKeySpace(),
          geoLocationDbInfo.getTableName(), id);
      List<Map<String, Object>> list = (List<Map<String, Object>>) response1.get(JsonKey.RESPONSE);
      finalResponse.put(JsonKey.RESPONSE, list);
      sender().tell(finalResponse, self());
      return;
    } else {
      throw new ProjectCommonException(ResponseCode.invalidTypeValue.getErrorCode(),
          ResponseCode.invalidTypeValue.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());
    }
  }

  /**
   * Create geo location , and id and topic value will be same .
   * 
   * @param actorMessage
   */
  private void createGeoLocation(Request actorMessage) {

    ProjectLogger.log("GeoLocationManagementActor-createGeoLocation called");

    // object of telemetry event...
    Map<String, Object> targetObject = null;
    List<Map<String, Object>> correlatedObject = new ArrayList<>();

    List<Map<String, Object>> dataList =
        (List<Map<String, Object>>) actorMessage.getRequest().get(JsonKey.DATA);

    Response finalResponse = new Response();
    List<Map<String, Object>> responseList = new ArrayList<>();
    String requestedBy = (String) actorMessage.getRequest().get(JsonKey.REQUESTED_BY);
    String rootOrgId = (String) actorMessage.getRequest().get(JsonKey.ROOT_ORG_ID);


    if (ProjectUtil.isStringNullOREmpty(rootOrgId)) {
      // throw invalid ord id ,org id should not be null or empty .
      throw new ProjectCommonException(ResponseCode.invalidOrgId.getErrorCode(),
          ResponseCode.invalidOrgId.getErrorMessage(), ResponseCode.CLIENT_ERROR.getResponseCode());
    }
    // check whether org exist or not
    Response result = cassandraOperation.getRecordById(orgDbInfo.getKeySpace(),
        orgDbInfo.getTableName(), rootOrgId);
    List<Map<String, Object>> orglist = (List<Map<String, Object>>) result.get(JsonKey.RESPONSE);
    if (orglist.isEmpty()) {
      throw new ProjectCommonException(ResponseCode.invalidOrgId.getErrorCode(),
          ResponseCode.invalidOrgId.getErrorMessage(), ResponseCode.CLIENT_ERROR.getResponseCode());
    }
    if (dataList.isEmpty()) {
      // no need to do anything throw exception invalid request data as list is empty
      throw new ProjectCommonException(ResponseCode.invalidRequestData.getErrorCode(),
          ResponseCode.invalidRequestData.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());
    }

    for (Map<String, Object> dataMap : dataList) {

      String location = (String) dataMap.get(JsonKey.LOCATION);
      String type = (String) dataMap.get(JsonKey.TYPE);
      if (ProjectUtil.isStringNullOREmpty(location)) {
        continue;
      }

      String id = ProjectUtil.getUniqueIdFromTimestamp(actorMessage.getEnv());

      Map<String, Object> dbMap = new HashMap<>();

      dbMap.put(JsonKey.ID, id);
      dbMap.put(JsonKey.CREATED_DATE, format.format(new Date()));
      dbMap.put(JsonKey.CREATED_BY, requestedBy);
      dbMap.put(JsonKey.ROOT_ORG_ID, rootOrgId);
      dbMap.put(JsonKey.LOCATION, location);
      dbMap.put(JsonKey.TOPIC, id);
      dbMap.put(JsonKey.TYPE, type);

      cassandraOperation.insertRecord(geoLocationDbInfo.getKeySpace(),
          geoLocationDbInfo.getTableName(), dbMap);

      Map<String, Object> responseMap = new HashMap<>();
      responseMap.put(JsonKey.ID, id);
      responseMap.put(JsonKey.LOCATION, location);
      responseMap.put(JsonKey.STATUS, JsonKey.SUCCESS);
      responseList.add(responseMap);

      targetObject = TelemetryUtil.generateTargetObject(id, JsonKey.LOCATION, JsonKey.CREATE, null);
      TelemetryUtil.generateCorrelatedObject(id, JsonKey.LOCATION, null, correlatedObject);
      TelemetryUtil.generateCorrelatedObject(rootOrgId, JsonKey.ROOT_ORG, null, correlatedObject);
      TelemetryUtil.telemetryProcessingCall(actorMessage.getRequest(), targetObject,
          correlatedObject);

    }
    finalResponse.getResult().put(JsonKey.RESPONSE, responseList);
    sender().tell(finalResponse, self());



  }

}
