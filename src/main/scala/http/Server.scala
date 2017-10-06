package http

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{complete, _}
import akka.http.scaladsl.server.{MalformedRequestContentRejection, RejectionHandler}
import akka.stream.ActorMaterializer
import services._

import scala.collection.mutable

class Server extends JsonSupport {

  implicit val system = ActorSystem("my-system")
  implicit val materializer = ActorMaterializer()
  // needed for the future flatMap/onComplete in the end
  implicit val executionContext = system.dispatcher

  //  var db = Map.empty[String,BankAccount]
  val service = new AccountService {
    override val db: mutable.Map[String, BankAccount] = mutable.Map.empty
  }

  val regectionHandler = RejectionHandler.newBuilder()
    //    .handleNotFound { complete((StatusCodes.NotFound, "Oh man, what you are looking for is long gone.")) }
    .handle { case MalformedRequestContentRejection(msg, _) => complete((StatusCodes.InternalServerError, InternalError(msg))) }
    .result()

  val route = handleRejections(regectionHandler) {
    pathPrefix("account") {
      get {
        path(IntNumber) { id =>
          service.get(s"$id") match {
            case Right(x) => complete(StatusCodes.OK, x)
            case Left(y) => complete(StatusCodes.NotFound, ErrorResponse(y))
          }
        } ~
          pathEnd {
            complete(StatusCodes.NotImplemented, InternalError())
          }
      } ~
        post {
          service.create() match {
            case Some(x) => complete(StatusCodes.Created, x)
            case _ => complete(StatusCodes.InternalServerError, ErrorResponse(InternalError()))
          }
        }
    } ~
      pathPrefix("withdraw") {
        post {
          entity(as[WithdrawRequest]) { withdrawRequest =>
            service.withdraw(withdrawRequest) match {
              case Right(acc) => complete(StatusCodes.OK, acc)
              case Left(err) => complete(StatusCodes.InternalServerError, ErrorResponse(err))
            }
          }
        }
      } ~
      pathPrefix("transfer") {
        post {
          entity(as[TransferRequest]) { transferRequest =>
            service.transfer(transferRequest) match {
              case Right(acc) => complete(StatusCodes.OK, acc)
              case Left(err) => complete(StatusCodes.BadRequest, ErrorResponse(err))
            }
          }
        }
      }
  }


  def start = {
    Http().bindAndHandle(route, "localhost", 8080)
  }

  def stop = {
    system.terminate()
  }

}

object Server extends App {

}