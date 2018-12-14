package services

import java.net.URL
import javax.inject.Inject

import model._
import play.api.Logger
import play.api.libs.json._
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.{ExecutionContext, Future}

trait NotificationSender {
  def sendNotification(uploadedFile: ProcessedFile): Future[Unit]
}

case class ReadyCallbackBody(
  reference: Reference,
  downloadUrl: URL,
  fileStatus: FileStatus = ReadyFileStatus,
  uploadDetails: UploadDetails
)

object ReadyCallbackBody {
  import JsonWriteHelpers.urlFormats

  implicit val writesReadyCallback: Writes[ReadyCallbackBody] = Json.writes[ReadyCallbackBody]
}

case class FailedCallbackBody(
  reference: Reference,
  fileStatus: FileStatus = FailedFileStatus,
  failureDetails: ErrorDetails
)

object FailedCallbackBody {
  implicit val writesFailedCallback: Writes[FailedCallbackBody] = Json.writes[FailedCallbackBody]
}

sealed trait FileStatus {
  val status: String
}
case object ReadyFileStatus extends FileStatus {
  override val status: String = "READY"
}
case object FailedFileStatus extends FileStatus {
  override val status: String = "FAILED"
}

object FileStatus {
  implicit val fileStatusWrites: Writes[FileStatus] = new Writes[FileStatus] {
    override def writes(o: FileStatus): JsValue = JsString(o.status)
  }
}

case class ErrorDetails(failureReason: String, message: String)

object ErrorDetails {
  implicit val formatsErrorDetails: Format[ErrorDetails] = Json.format[ErrorDetails]
}

class HttpNotificationSender @Inject()(httpClient: HttpClient)(implicit ec: ExecutionContext)
    extends NotificationSender {

  override def sendNotification(uploadedFile: ProcessedFile): Future[Unit] = uploadedFile match {
    case f: UploadedFile    => notifySuccessfulCallback(f)
    case f: QuarantinedFile => notifyFailedCallback(f)
  }

  private def notifySuccessfulCallback(uploadedFile: UploadedFile): Future[Unit] = {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val callback =
      ReadyCallbackBody(uploadedFile.reference, uploadedFile.downloadUrl, uploadDetails = uploadedFile.uploadDetails)

    httpClient
      .POST[ReadyCallbackBody, HttpResponse](uploadedFile.callbackUrl.toString, callback)
      .map { httpResponse =>
        Logger.info(
          s"""File ready notification: [${callback}], sent to service with callbackUrl: [${uploadedFile.callbackUrl}].
             | Response status was: [${httpResponse.status}].""".stripMargin
        )
      }
  }

  private def notifyFailedCallback(quarantinedFile: QuarantinedFile): Future[Unit] = {

    implicit val hc: HeaderCarrier = HeaderCarrier()
    val errorDetails               = ErrorDetails("QUARANTINE", quarantinedFile.error)
    val callback =
      FailedCallbackBody(quarantinedFile.reference, failureDetails = errorDetails)

    httpClient
      .POST[FailedCallbackBody, HttpResponse](quarantinedFile.callbackUrl.toString, callback)
      .map { httpResponse =>
        Logger.info(
          s"""File failed notification: [${callback}], sent to service with callbackUrl: [${quarantinedFile.callbackUrl}].
             | Response status was: [${httpResponse.status}].""".stripMargin
        )
      }
  }

}
