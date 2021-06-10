package org.sunbird.job.certgen.functions

import java.lang.reflect.Type
import java.text.SimpleDateFormat
import java.util
import java.util.Date

import com.datastax.driver.core.querybuilder.{QueryBuilder, Select}
import com.datastax.driver.core.{Row, TypeTokens}
import com.google.gson.reflect.TypeToken
import org.apache.commons.lang3.StringUtils
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.certgen.task.CertificateGeneratorConfig
import org.sunbird.job.util.{CassandraUtil, HTTPResponse, HttpUtil, JSONUtil, ScalaJsonUtil}
import org.sunbird.job.{BaseProcessFunction, Metrics}

import scala.collection.JavaConverters._
import scala.collection.mutable

case class NotificationMetaData(userId: String, courseName: String, issuedOn: Date, courseId: String, batchId: String, templateId: String)

class NotifierFunction(config: CertificateGeneratorConfig, httpUtil: HttpUtil, @transient var cassandraUtil: CassandraUtil = null)(implicit val stringTypeInfo: TypeInformation[String])
  extends BaseProcessFunction[NotificationMetaData, String](config) {

  private val dateFormatter = new SimpleDateFormat("yyyy-MM-dd")

  private[this] val logger = LoggerFactory.getLogger(classOf[NotifierFunction])

  val mapType: Type = new TypeToken[java.util.Map[String, AnyRef]]() {}.getType

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    cassandraUtil = new CassandraUtil(config.dbHost, config.dbPort)
  }

  override def close(): Unit = {
    cassandraUtil.close()
    super.close()
  }

  override def processElement(metaData: NotificationMetaData,
                              context: ProcessFunction[NotificationMetaData, String]#Context,
                              metrics: Metrics): Unit = {

    val userResponse: Map[String, AnyRef] = getUserDetails(metaData.userId) // call user Service
    val primaryFields = Map(config.courseId.toLowerCase() -> metaData.courseId, config.batchId.toLowerCase -> metaData.batchId)
    val row = getNotificationTemplates(primaryFields, metrics)
    if (null != userResponse && userResponse.nonEmpty) {
      val certTemplate = row.getMap(config.cert_templates, com.google.common.reflect.TypeToken.of(classOf[String]),
        TypeTokens.mapOf(classOf[String], classOf[String]))
      val url = config.learnerServiceBaseUrl + config.notificationEndPoint
      if (certTemplate != null && StringUtils.isNotBlank(metaData.templateId) &&
        certTemplate.containsKey(metaData.templateId) &&
        certTemplate.get(metaData.templateId).containsKey(config.notifyTemplate)) {
        logger.info("notification template is present in the cert-templates object {}",
          certTemplate.get(metaData.templateId).containsKey(config.notifyTemplate))
        val notifyTemplate = getNotifyTemplateFromRes(certTemplate.get(metaData.templateId))
        val request = mutable.Map[String, AnyRef]("request" -> (notifyTemplate ++ mutable.Map[String, AnyRef](
          config.firstName -> userResponse.getOrElse(config.firstName, "").asInstanceOf[String],
          config.trainingName -> metaData.courseName,
          config.heldDate -> dateFormatter.format(metaData.issuedOn),
          config.recipientUserIds -> List[String](metaData.userId),
          config.body -> "email body")))
        
        /*val request = s"""{"request": {${notifyTemplate}, "${config.firstName}": "${
          userResponse.get(config.firstName).asInstanceOf[String]
        }", "${config.trainingName}": "${metaData.courseName}", "${config.heldDate}": "${
          dateFormatter.format(metaData.issuedOn)
        }", ${config.recipientUserIds}:"[${metaData.userId}]", "${config.body}": "email body"}}"""*/
        val response = httpUtil.post(url, ScalaJsonUtil.serialize(request))
        if (response.status == 200) {
          metrics.incCounter(config.notifiedUserCount)
          logger.info("email response status {} :: {}", response.status, response.body)
        }
        else throw new Exception(s"Error in email response ${response}")
        if (StringUtils.isNoneBlank(userResponse.getOrElse("maskedPhone", "").asInstanceOf[String])) {
          request.put(config.body, "sms")
          val smsBody = config.notificationSmsBody.replaceAll("@@TRAINING_NAME@@", metaData.courseName)
            .replaceAll("@@HELD_DATE@@", dateFormatter.format(metaData.issuedOn))
          request.getOrElse("request", mutable.Map[String, AnyRef]()).asInstanceOf[mutable.Map[String, AnyRef]].put("body", smsBody)
          val response = httpUtil.post(url, ScalaJsonUtil.serialize(request))
          if (response.status == 200)
            logger.info("phone response status {} :: {}", response.status, response.body)
          else
            throw new Exception(s"email response status ${response} :: {}")
        }
      } else {
        logger.info("notification template is not present in the cert-templates object {}")
        metrics.incCounter(config.skipNotifyUserCount)
      }
    }
  }


  private def getNotifyTemplateFromRes(certTemplate: util.Map[String, String]): mutable.Map[String, String] = {
    val notifyTemplate = certTemplate.get(config.notifyTemplate)
    if (notifyTemplate.isInstanceOf[String]) ScalaJsonUtil.deserialize[mutable.Map[String, String]](notifyTemplate)
    else notifyTemplate.asInstanceOf[mutable.Map[String, String]]
  }


  /**
    * get notify template  from course-batch table
    *
    * @param columns
    * @param metrics
    * @return
    */
  private def getNotificationTemplates(columns: Map[String, AnyRef], metrics: Metrics): Row = {
    val selectWhere: Select.Where = QueryBuilder.select().all()
      .from(config.dbKeyspace, config.dbCourseBatchTable).
      where()
    columns.map(col => {
      col._2 match {
        case value: List[Any] =>
          selectWhere.and(QueryBuilder.in(col._1, value.asJava))
        case _ =>
          selectWhere.and(QueryBuilder.eq(col._1, col._2))
      }
    })
    metrics.incCounter(config.courseBatchdbReadCount)
    cassandraUtil.findOne(selectWhere.toString)
  }


  private def getUserDetails(userId: String): Map[String, AnyRef] = {
    logger.info("getting user info for id {}", userId)
    httpUtil.get(config.learnerServiceBaseUrl + "/private/user/v1/read/" + "85b48474-ef7e-47be-b301-8901a6cdd346")
    val httpResponse = HTTPResponse(200, """{"id":".private.user.v1.read.85b48474-ef7e-47be-b301-8901a6cdd346","ver":"private","ts":"2021-06-09 05:36:00:005+0000","params":{"resmsgid":null,"msgid":"2564aa99-e2db-45dd-b828-0422821d6da9","err":null,"status":"success","errmsg":null},"responseCode":"OK","result":{"response":{"webPages":[],"maskedPhone":null,"tcStatus":null,"subject":[],"channel":"dikshapreprodcustodian","language":null,"updatedDate":"2021-03-12 04:18:18:911+0000","password":null,"managedBy":null,"flagsValue":2,"id":"85b48474-ef7e-47be-b301-8901a6cdd346","recoveryEmail":"","identifier":"85b48474-ef7e-47be-b301-8901a6cdd346","thumbnail":null,"profileVisibility":null,"updatedBy":"85b48474-ef7e-47be-b301-8901a6cdd346","accesscode":null,"locationIds":[],"registryId":null,"rootOrgId":"0126796199493140480","prevUsedEmail":"","firstName":"1612351465-54","profileLocation":[],"tncAcceptedOn":1613101923680,"allTncAccepted":{},"phone":"","dob":null,"grade":null,"currentLoginTime":null,"userType":"student","status":1,"lastName":null,"tncLatestVersion":"v27","gender":null,"roles":["PUBLIC"],"prevUsedPhone":"","stateValidated":false,"encEmail":"paFjjDIfL/FhDW8a4lyMvoXX6h4sjQGWOLg09YFEtGuqO2z7mOOsetmCeWtcXgAApqS89gcech2B\nAXVXpU5LfPGAaxmJf9ayLXfrC15jPAdGb1yEKt6FUXv+nL5wm2ZmUpKcwa0WpYc9AfjPPg5chD5A\ncxY9FTs76gIdQMsJk4w=","isDeleted":false,"organisations":[{"organisationId":"0126796199493140480","updatedBy":null,"addedByName":null,"addedBy":null,"roles":["PUBLIC"],"approvedBy":null,"updatedDate":null,"userId":"85b48474-ef7e-47be-b301-8901a6cdd346","approvaldate":null,"isDeleted":false,"hashTagId":"0126796199493140480","isRejected":null,"id":"0132084357786173445","position":null,"isApproved":null,"orgjoindate":"2021-02-03 12:57:16:794+0000","orgLeftDate":null}],"provider":null,"countryCode":null,"tncLatestVersionUrl":"https://dev-sunbird-temp.azureedge.net/portal/terms-and-conditions-v1.html","maskedEmail":"16***********@yopmail.com","tempPassword":null,"email":"16***********@yopmail.com","rootOrg":{"dateTime":null,"preferredLanguage":null,"keys":{},"approvedBy":null,"channel":"dikshapreprodcustodian","description":"Pre-prod Custodian Organization","updatedDate":null,"addressId":null,"organisationType":5,"orgType":null,"isTenant":true,"provider":null,"locationId":null,"orgCode":null,"theme":null,"id":"0126796199493140480","communityId":null,"isApproved":null,"email":null,"slug":"dikshapreprodcustodian","isSSOEnabled":null,"thumbnail":null,"orgName":"Pre-prod Custodian Organization","updatedBy":null,"locationIds":[],"externalId":null,"orgLocation":[],"isRootOrg":true,"rootOrgId":"0126796199493140480","approvedDate":null,"imgUrl":null,"homeUrl":null,"orgTypeId":null,"isDefault":null,"createdDate":"2019-01-18 09:48:13:428+0000","createdBy":"system","parentOrgId":null,"hashTagId":"0126796199493140480","noOfMembers":null,"status":null},"phoneVerified":false,"profileSummary":null,"tcUpdatedDate":null,"recoveryPhone":"","avatar":null,"userName":"161235146554_x5c2","userId":"85b48474-ef7e-47be-b301-8901a6cdd346","userSubType":null,"promptTnC":true,"emailVerified":true,"lastLoginTime":null,"createdDate":"2021-02-03 12:57:15:219+0000","framework":{"board":["CBSE"],"gradeLevel":["Class 1"],"id":["NCF"],"medium":["Bengali"],"subject":["Accountancy Auditing"]},"createdBy":null,"profileUserType":{"type":"student"},"encPhone":null,"location":null,"tncAcceptedVersion":"v23"}}}""")
    val response = ScalaJsonUtil.deserialize[Map[String, AnyRef]](httpResponse.body)
    val result = response.getOrElse("result", Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]].getOrElse("response", Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]]
    result
    
    /*val httpResponse = httpUtil.get(config.learnerServiceBaseUrl + "/private/user/v1/read/" + "85b48474-ef7e-47be-b301-8901a6cdd346")
    if (httpResponse.status == 200) {
      logger.info("user search response status {} :: {} ", httpResponse.status, httpResponse.body)
      val response = ScalaJsonUtil.deserialize[Map[String, AnyRef]](httpResponse.body)
      val result = response.getOrElse("result", Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]].getOrElse("response", Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]]
      result
    } else throw new Exception(s"Error while reading user for notification for userId: ${userId}")*/
  }

  override def metricsList(): List[String] = {
    List(config.courseBatchdbReadCount, config.skipNotifyUserCount, config.notifiedUserCount)
  }


}
