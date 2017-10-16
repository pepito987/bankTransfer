package services

import java.util.UUID

import common.ServiceAware
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import spray.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scalaj.http.Http


class TransactionTransferSpec extends ServiceAware with Matchers with JsonSupport with ScalaFutures {

  "POST /transaction/transfer" should {
    "return 400 if the body is empty and log the transaction" in {

      val response = Http("http://localhost:8080/transaction/transfer")
        .header("Content-Type", "application/json")
        .postData("").asString

      response.code shouldBe 400
      response.header("Content-Type").get shouldBe "application/json"
      response.body.parseJson.convertTo[ErrorResponse].reason shouldBe RequestNotValid().errorMessage
    }

    "return 404 if the from account does not exist and store the transaction" in {

      val from = "123"
      val to = "987"
      val amount = 50

      server.service.accountsDB.put(to, BankAccount(to, 200))

      val transfer = Transfer(from, to, amount)
      val response = Http("http://localhost:8080/transaction/transfer")
        .header("Content-Type", "application/json")
        .postData(
          transfer.toJson.toString()
        ).asString

      response.code shouldBe 404
      response.header("Content-Type").get shouldBe "application/json"
      response.body.parseJson.convertTo[FailedTransactionResponse].reason shouldBe AccountNotFound().errorMessage

      server.service.transactionsDB.values
        .collect{case x:FailedTransaction => x}
        .find(tx => tx.request == transfer)
    }

    "return 404 if the dst account does not exist" in {
      val from = "123"
      val to = "987"
      val amount = 50

      server.service.accountsDB.put(from, BankAccount(from, 200))

      val transfer = Transfer(from, to, amount)
      val response = Http("http://localhost:8080/transaction/transfer")
        .header("Content-Type", "application/json")
        .postData(
          transfer.toJson.toString()
        ).asString

      response.code shouldBe 404
      response.header("Content-Type").get shouldBe "application/json"
      response.body.parseJson.convertTo[FailedTransactionResponse].reason shouldBe AccountNotFound().errorMessage

      server.service.transactionsDB.values
        .collect{case x:FailedTransaction => x}
        .find(tx => tx.request == transfer)
    }

    "rollback if the deposit on a transaction fails" in {
      val from = "123"
      val to = "987"
      val amount = 50

      server.service.accountsDB.put(from, BankAccount(from, 200))

      val transfer = Transfer(from, to, amount)
      val response = Http("http://localhost:8080/transaction/transfer")
        .header("Content-Type", "application/json")
        .postData(
          transfer.toJson.toString()
        ).asString

      response.code shouldBe 404
      server.service.accountsDB(from).balance shouldBe 200

      server.service.transactionsDB.values
        .collect{case x:FailedTransaction => x}
        .find(tx => tx.request == transfer)

    }

    "return 400 if insufficient found and will not update the accounts" in {

      val amount = 500
      val from = BankAccount("123", 200)
      val to = BankAccount("987", 200)

      server.service.accountsDB.put(from.id, from)
      server.service.accountsDB.put(to.id, to)

      val transfer = Transfer(from.id, to.id, amount)
      val response = Http("http://localhost:8080/transaction/transfer")
        .header("Content-Type", "application/json")
        .postData(
          transfer.toJson.toString()
        ).asString

      response.code shouldBe 400
      response.header("Content-Type").get shouldBe "application/json"
      response.body.parseJson.convertTo[FailedTransactionResponse].reason shouldBe InsufficientFund().errorMessage

      server.service.accountsDB(from.id) shouldBe from
      server.service.accountsDB(to.id) shouldBe to

      server.service.transactionsDB.values
        .collect{case x:FailedTransaction => x}
        .find(tx => tx.request == transfer)
    }

    "return 200 and the balance reflect the transfer" in {

      val from = "123"
      val to = "987"
      val amount = 50

      server.service.accountsDB.put(from, BankAccount(from, 200))
      server.service.accountsDB.put(to, BankAccount(to, 200))

      val transfer = Transfer(from, to, amount)
      val response = Http("http://localhost:8080/transaction/transfer")
        .header("Content-Type", "application/json")
        .postData(
          transfer.toJson.toString()
        ).asString

      response.code shouldBe 200
      response.header("Content-Type").get shouldBe "application/json"
      response.body.parseJson.convertTo[SuccessTransactionResponse].balance shouldBe 150
      val fromAcc = server.service.accountsDB(from)
      val toAcc = server.service.accountsDB(to)

      fromAcc.balance shouldBe 150
      toAcc.balance shouldBe 250

      server.service.transactionsDB.values
        .collect{case x:FailedTransaction => x}
        .find(tx => tx.request == transfer)
    }

    "return 400 if the amount is not valid" in {
      val from = "123"
      val to = "987"
      val amount = -50

      server.service.accountsDB.put(from, BankAccount(from, 200))
      server.service.accountsDB.put(to, BankAccount(to, 200))

      val transfer = Transfer(from, to, amount)
      val response = Http("http://localhost:8080/transaction/transfer")
        .header("Content-Type", "application/json")
        .postData(
          transfer.toJson.toString()
        ).asString

      response.code shouldBe 400
      response.header("Content-Type").get shouldBe "application/json"
      response.body.parseJson.convertTo[FailedTransactionResponse].reason shouldBe AmountNotValid().errorMessage

      server.service.transactionsDB.values
        .collect{case x:FailedTransaction => x}
        .find(tx => tx.request == transfer)
    }
    "handle concurrency" in {

      for( _ <- 1 until 2000) {
        server.service.accountsDB.put("bob", BankAccount("bob", 200))
        server.service.accountsDB.put("alice", BankAccount("alice", 200))
        server.service.accountsDB.put("john", BankAccount("john", 200))

        val requests = scala.util.Random.shuffle(List(
          Transfer("bob", "alice", 50),
          Transfer("alice", "john", 50)))

        val responses = requests.map(r => Future {
          Http("http://localhost:8080/transaction/transfer")
            .header("Content-Type", "application/json")
            .postData(
              r.toJson.toString()
            ).asString
        })

        Future.sequence(responses).futureValue.foreach(response => {
          response.code shouldBe 200
          response.header("Content-Type").get shouldBe "application/json"
        })

        server.service.accountsDB("alice").balance shouldBe 200
        server.service.accountsDB("bob").balance shouldBe 150
        server.service.accountsDB("john").balance shouldBe 250
      }
    }
  }
  "GET on /transaction/tx/{id} " should {
    "return a transaction " in {

      val uuid = UUID.randomUUID().toString
      val tx = SuccessTransaction(uuid,Transfer("111", "222", 90),87)

      server.service.transactionsDB.put(tx.id,tx)

      val response = Http(s"http://localhost:8080/transaction/tx?id=${uuid}")
        .header("Content-Type", "application/x-www-form-urlencoded").asString

      response.code shouldBe 200
      response.header("Content-Type").get shouldBe "application/json"
      response.body.parseJson.convertTo[SuccessTransactionResponse].balance shouldBe 87
    }
  }

}
