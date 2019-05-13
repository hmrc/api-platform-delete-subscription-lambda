package uk.gov.hmrc.apiplatform.deletesubscription

import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger}
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{times, verify, verifyZeroInteractions, when}
import org.scalatest.{Matchers, WordSpecLike}
import org.scalatest.mockito.MockitoSugar
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.{GetRestApisRequest, GetRestApisResponse, GetUsagePlansRequest, GetUsagePlansResponse, NotFoundException, Op, PatchOperation, RestApi, UpdateUsagePlanRequest, UpdateUsagePlanResponse, UsagePlan}
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.JsonMapper

import scala.collection.JavaConversions._

class DeleteSubscriptionHandlerSpec extends WordSpecLike with MockitoSugar with Matchers with JsonMapper {

  trait Setup {
    def buildDeleteSubscriptionMessage(applicationName: String, apiName: String): SQSMessage = {
      val sqsMessage = new SQSMessage
      sqsMessage.setBody(s"""{ "applicationName": "$applicationName", "apiName": "$apiName" }""")

      sqsMessage
    }

    def buildUsagePlansResponse(usagePlanId: String, applicationName: String): GetUsagePlansResponse =
      GetUsagePlansResponse.builder()
        .items(UsagePlan.builder().id(usagePlanId).name(applicationName).build())
        .build()

    def buildRestApisResponse(restApiId: String, apiName: String): GetRestApisResponse =
      GetRestApisResponse.builder()
        .items(RestApi.builder().id(restApiId).name(apiName).build())
        .build()

    val usagePlanId: String = UUID.randomUUID().toString
    val restAPIId: String = UUID.randomUUID().toString

    val applicationName = "application-1"
    val apiName = "api--1.0"

    val expectedApiStageName = s"$restAPIId:current"

    val mockAPIGatewayClient: ApiGatewayClient = mock[ApiGatewayClient]
    val mockContext: Context = mock[Context]
    when(mockContext.getLogger).thenReturn(mock[LambdaLogger])

    val environment: Map[String, String] = Map()

    val deleteSubscriptionHandler = new DeleteSubscriptionHandler(mockAPIGatewayClient, environment)
  }

  trait ExistingApplicationAndAPI extends Setup {
    when(mockAPIGatewayClient.getUsagePlans(any[GetUsagePlansRequest])).thenReturn(buildUsagePlansResponse(usagePlanId, applicationName))
    when(mockAPIGatewayClient.getRestApis(any[GetRestApisRequest])).thenReturn(buildRestApisResponse(restAPIId, apiName))
  }

  trait UnknownApplication extends Setup {
    when(mockAPIGatewayClient.getUsagePlans(any[GetUsagePlansRequest])).thenReturn(GetUsagePlansResponse.builder().build())
    when(mockAPIGatewayClient.getRestApis(any[GetRestApisRequest])).thenReturn(buildRestApisResponse(restAPIId, apiName))
  }

  trait UnknownAPI extends Setup {
    when(mockAPIGatewayClient.getUsagePlans(any[GetUsagePlansRequest])).thenReturn(buildUsagePlansResponse(usagePlanId, applicationName))
    when(mockAPIGatewayClient.getRestApis(any[GetRestApisRequest])).thenReturn(GetRestApisResponse.builder().build())
  }

  "Delete Subscription Handler" should {
    "subscribe Application to API" in new ExistingApplicationAndAPI {
      val sqsEvent = new SQSEvent()
      sqsEvent.setRecords(List(buildDeleteSubscriptionMessage(applicationName, apiName)))

      val addSubscriptionRequestCaptor: ArgumentCaptor[UpdateUsagePlanRequest] = ArgumentCaptor.forClass(classOf[UpdateUsagePlanRequest])
      when(mockAPIGatewayClient.updateUsagePlan(addSubscriptionRequestCaptor.capture())).thenReturn(UpdateUsagePlanResponse.builder().id(usagePlanId).build())

      deleteSubscriptionHandler.handleInput(sqsEvent, mockContext)

      val capturedRequest: UpdateUsagePlanRequest = addSubscriptionRequestCaptor.getValue
      capturedRequest.patchOperations().size() == 1

      val capturedPatchRequest: PatchOperation = capturedRequest.patchOperations().get(0)
      capturedPatchRequest.op() shouldEqual Op.REMOVE
      capturedPatchRequest.path() shouldEqual "/apiStages"
      capturedPatchRequest.value() shouldEqual expectedApiStageName
    }

    "throw exception if Application name is not recognised" in new UnknownApplication {
      val sqsEvent = new SQSEvent()
      sqsEvent.setRecords(List(buildDeleteSubscriptionMessage(applicationName, apiName)))

      val exception: Exception = intercept[Exception](deleteSubscriptionHandler.handleInput(sqsEvent, mockContext))

      exception shouldBe a[NotFoundException]
      verify(mockAPIGatewayClient, times(0)).updateUsagePlan(any[UpdateUsagePlanRequest])
    }

    "throw exception if API name is not recognised" in new UnknownAPI {
      val sqsEvent = new SQSEvent()
      sqsEvent.setRecords(List(buildDeleteSubscriptionMessage(applicationName, apiName)))

      val exception: Exception = intercept[Exception](deleteSubscriptionHandler.handleInput(sqsEvent, mockContext))

      exception shouldBe a[NotFoundException]
      verify(mockAPIGatewayClient, times(0)).updateUsagePlan(any[UpdateUsagePlanRequest])
    }

    "throw exception if the event has no messages" in new Setup {
      val sqsEvent = new SQSEvent()
      sqsEvent.setRecords(List())

      val exception: IllegalArgumentException = intercept[IllegalArgumentException](deleteSubscriptionHandler.handleInput(sqsEvent, mockContext))
      exception.getMessage shouldEqual "Invalid number of records: 0"

      verifyZeroInteractions(mockAPIGatewayClient)
    }

    "throw exception if the event has multiple messages" in new Setup {
      val sqsEvent = new SQSEvent()
      sqsEvent.setRecords(
        List(
          buildDeleteSubscriptionMessage("application-1", "api-1"),
          buildDeleteSubscriptionMessage("application-2", "api-2")))

      val exception: IllegalArgumentException = intercept[IllegalArgumentException](deleteSubscriptionHandler.handleInput(sqsEvent, mockContext))
      exception.getMessage shouldEqual "Invalid number of records: 2"

      verifyZeroInteractions(mockAPIGatewayClient)
    }
  }
}
