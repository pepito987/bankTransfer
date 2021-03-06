package services

import java.util.UUID

import common.ServiceAware
import org.joda.time.DateTime
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scalaj.http.Http


class TransactionTransferSpec extends ServiceAware with Matchers with JsonSupport with ScalaFutures {

  "POST /account/{id}/transfer" should {
    "return 400 if the body is empty and log the transaction" in {

      val response = Http("http://localhost:8080/account/000/transfer")
        .header("Content-Type", "application/json")
        .postData("").asString

      response.code shouldBe 400
      response.header("Content-Type").get shouldBe "application/json"
    }

    "return 404 if the source account does not exist and store the transaction" in {

      val from = "123"
      val to = "987"
      val amount = 50

      server.service.accountsDB.put(to, BankAccount(to, "bob", 200))

      val transfer = BiTransaction(to, amount)
      val response = Http("http://localhost:8080/account/000/transfer")
        .header("Content-Type", "application/json")
        .postData(
          transfer.toJson.toString()
        ).asString

      response.code shouldBe 404
      response.header("Content-Type").get shouldBe "application/json"
      val body = response.body.parseJson.convertTo[FailedTransactionResponse]
      body.reason shouldBe AccountNotFound().errorMessage

      server.service.transactionsDB.contains(body.id) shouldBe true
    }

    "return 404 if the destination account does not exist" in {
      val from = "123"
      val to = "987"
      val amount = 50

      server.service.accountsDB.put(from, BankAccount(from, "bob", 200))

      val transfer = BiTransaction(to, amount)
      val response = Http("http://localhost:8080/account/123/transfer")
        .header("Content-Type", "application/json")
        .postData(
          transfer.toJson.toString()
        ).asString

      response.code shouldBe 404
      response.header("Content-Type").get shouldBe "application/json"
      val body = response.body.parseJson.convertTo[FailedTransactionResponse]
      body.reason shouldBe AccountNotFound().errorMessage

      server.service.transactionsDB.contains(body.id) shouldBe true
    }

    "rollback if the deposit on a transaction fails" in {
      val from = "123"
      val to = "987"
      val amount = 50

      server.service.accountsDB.put(from, BankAccount(from, "bob", 200))

      val transfer = BiTransaction(to, amount)
      val response = Http("http://localhost:8080/account/123/transfer")
        .header("Content-Type", "application/json")
        .postData(
          transfer.toJson.toString()
        ).asString

      response.code shouldBe 404
      server.service.accountsDB(from).balance shouldBe 200

      //      server.service.transactionsDB.values
      //        .collect{case x:FailedTransaction => x}
      //        .find(tx => tx.request.isInstanceOf[Transaction])
      //        .get.request.asInstanceOf[Transaction].amount shouldBe true

    }

    "return 400 if insufficient found and will not update the accounts" in {

      val amount = 500
      val from = BankAccount("123", "bob", 200)
      val to = BankAccount("987", "bob", 200)

      server.service.accountsDB.put(from.id, from)
      server.service.accountsDB.put(to.id, to)

      val transfer = BiTransaction(to.id, amount)
      val response = Http("http://localhost:8080/account/123/transfer")
        .header("Content-Type", "application/json")
        .postData(
          transfer.toJson.toString()
        ).asString

      response.code shouldBe 400
      response.header("Content-Type").get shouldBe "application/json"
      val body = response.body.parseJson.convertTo[FailedTransactionResponse]
      body.reason shouldBe InsufficientFund().errorMessage

      server.service.accountsDB(from.id) shouldBe from
      server.service.accountsDB(to.id) shouldBe to

      server.service.transactionsDB.contains(body.id) shouldBe true
    }

    "return 200 and the balance reflect the transfer" in {

      val from = "123"
      val to = "987"
      val amount = 50

      server.service.accountsDB.put(from, BankAccount(from, "bob", 200))
      server.service.accountsDB.put(to, BankAccount(to, "bob", 200))

      val transfer = BiTransaction(to, amount)
      val response = Http("http://localhost:8080/account/123/transfer")
        .header("Content-Type", "application/json")
        .postData(
          transfer.toJson.toString()
        ).asString

      response.code shouldBe 200
      response.header("Content-Type").get shouldBe "application/json"
      val body = response.body.parseJson.convertTo[SuccessTransactionResponse]
      body.balance shouldBe 50

      val fromAcc = server.service.accountsDB(from)
      val toAcc = server.service.accountsDB(to)

      fromAcc.balance shouldBe 150
      toAcc.balance shouldBe 250

      server.service.transactionsDB.contains(body.id) shouldBe true
    }

    "return 400 if the amount is not valid" in {
      val from = "123"
      val to = "987"
      val amount = -50

      server.service.accountsDB.put(from, BankAccount(from, "bob", 200))
      server.service.accountsDB.put(to, BankAccount(to, "bob", 200))

      val transfer = Transfer(from, to, amount)
      val response = Http("http://localhost:8080/account/123/transfer")
        .header("Content-Type", "application/json")
        .postData(
          transfer.toJson.toString()
        ).asString

      response.code shouldBe 400
      response.header("Content-Type").get shouldBe "application/json"
      val body = response.body.parseJson.convertTo[FailedTransactionResponse]
      body.reason shouldBe AmountNotValid().errorMessage

      server.service.transactionsDB.contains(body.id) shouldBe true
    }
    "handle concurrency" in {

      for( _ <- 1 until 200) {
        val bob = BankAccount("123", "bob", 200)
        val alice = BankAccount("345", "alice", 200)
        val john = BankAccount("678", "john", 200)
        server.service.accountsDB.put(bob.id, bob )
        server.service.accountsDB.put(alice.id, alice )
        server.service.accountsDB.put(john.id, john )

        val requests = scala.util.Random.shuffle(List(
          Transfer(bob.id, alice.id, 50),
          Transfer(alice.id, john.id, 50)))

        val responses = requests.map(r => Future {
          Http(s"http://localhost:8080/account/${r.from}/transfer")
            .header("Content-Type", "application/json")
            .postData(
              BiTransaction(r.to,r.amount).toJson.toString()
            ).asString
        })

        Future.sequence(responses).futureValue.foreach(response => {
          response.code shouldBe 200
          response.header("Content-Type").get shouldBe "application/json"
        })

        server.service.accountsDB("123").balance shouldBe 150
        server.service.accountsDB("345").balance shouldBe 200
        server.service.accountsDB("678").balance shouldBe 250
      }
    }
  }
  "GET on /account/{id}/tx/{tx_id} " should {
    "return 404 if the account does not exist" in {
      val response = Http(s"http://localhost:8080/account/123/tx/9999")
        .header("Content-Type", "application/x-www-form-urlencoded").asString

      response.code shouldBe 404
      response.header("Content-Type").get shouldBe "application/json"
      response.body.parseJson.convertTo[ErrorResponse].reason shouldBe AccountNotFound().errorMessage
    }

    "return 404 fi the transaction does not exist" in {
      server.service.accountsDB.put("123", BankAccount("123", "bob", 200))

      val response = Http(s"http://localhost:8080/account/123/tx/555")
        .header("Content-Type", "application/x-www-form-urlencoded").asString

      response.code shouldBe 404
      response.header("Content-Type").get shouldBe "application/json"
      response.body.parseJson.convertTo[ErrorResponse].reason shouldBe TransactionNotFound().errorMessage
    }

    "return 200 with a transaction " in {

      server.service.accountsDB.put("123", BankAccount("123", "bob", 200))
      val uuid = UUID.randomUUID().toString
      val tx = SuccessTransaction(uuid,Transaction("123", Some("222"), 87), DateTime.now())

      server.service.transactionsDB.put(tx.id,tx)

      val response = Http(s"http://localhost:8080/account/123/tx/${uuid}")
        .header("Content-Type", "application/x-www-form-urlencoded").asString

      response.code shouldBe 200
      response.header("Content-Type").get shouldBe "application/json"
      response.body.parseJson.convertTo[TransactionRecordResponse].amount.get shouldBe 87
    }
  }
  "GET on /account/{id}/txs" should {
    "return 404 if the account does not exist" in {

      val response = Http(s"http://localhost:8080/account/123/txs").asString

      response.code shouldBe 404
      response.body.parseJson.convertTo[ErrorResponse].reason shouldBe AccountNotFound().errorMessage
    }

    "return 200 with an empty list of transactions for the given user account" in {
      server.service.accountsDB.put("123", BankAccount("123", "bob", 200))

      val response = Http(s"http://localhost:8080/account/123/txs").asString

      response.code shouldBe 200
      val transactions: List[TransactionRecordResponse] = response.body.parseJson.convertTo[List[TransactionRecordResponse]]
      transactions shouldBe List.empty[TransactionRecordResponse]
    }

    "return 200 with a list of transactions for the given user account" in {
      server.service.accountsDB.put("123", BankAccount("123", "bob", 200))
      server.service.accountsDB.put("222", BankAccount("222", "John", 200))

      val tx1 = SuccessTransaction(UUID.randomUUID().toString,Transaction("123",Some("222"),87), DateTime.now())
      val tx2 = SuccessTransaction(UUID.randomUUID().toString,Transaction("123",Some("222"),67), DateTime.now())
      val tx3 = FailedTransaction(UUID.randomUUID().toString,Transaction("123",Some("000"),67), AccountNotFound(), DateTime.now())

      List(tx1, tx2, tx3).foreach {
        case tx: SuccessTransaction => server.service.transactionsDB.put(tx.id, tx)
        case tx: FailedTransaction => server.service.transactionsDB.put(tx.id, tx)
      }

      val srcAccountResponse = Http(s"http://localhost:8080/account/123/txs").asString
      val dstAccountResponse = Http(s"http://localhost:8080/account/222/txs").asString

      srcAccountResponse.code shouldBe 200
      val srcTransactions: List[TransactionRecordResponse] = srcAccountResponse.body.parseJson.convertTo[List[TransactionRecordResponse]]
      val dstTransactions: List[TransactionRecordResponse] = dstAccountResponse.body.parseJson.convertTo[List[TransactionRecordResponse]]
      println(srcAccountResponse.body.parseJson.prettyPrint)
      srcTransactions.size shouldEqual 3
      dstTransactions.size shouldEqual 2
    }

  }

}
