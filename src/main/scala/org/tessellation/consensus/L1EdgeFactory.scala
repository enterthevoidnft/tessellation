package org.tessellation.consensus

import cats.effect.IO
import cats.effect.concurrent.Ref
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import cats.implicits._
import fs2._

import scala.collection.immutable.TreeSet

class L1EdgeFactory(node: String)(implicit O: Ordering[L1Transaction]) {
  type Address = String
  type TransactionHash = String

  val logger = Slf4jLogger.getLogger[IO]

  private[consensus] val lastAccepted: Ref[IO, Map[Address, TransactionHash]] = Ref.unsafe(Map.empty)
  private[consensus] val waitingTransactions: Ref[IO, Map[Address, TreeSet[L1Transaction]]] = Ref.unsafe(Map.empty)
  private[consensus] val readyTransactions: Ref[IO, Map[Address, TreeSet[L1Transaction]]] = Ref.unsafe(Map.empty)
  private[consensus] val transactionsPerEdge = 5

  def createEdges: Pipe[IO, L1Transaction, L1Edge] = { incomingTransactions =>
    def optimizeWithReadyPool(incomingTransaction: L1Transaction): IO[TreeSet[L1Transaction]] =
      isParentAccepted(incomingTransaction)
        .ifM(
          logger.debug(s"[$node][Accepted] ${incomingTransaction}") >> dequeue1ReadyTransactions()
            .map(_ + incomingTransaction),
          logger
            .debug(s"[$node][NotAccepted (put to WaitingPool)] ${incomingTransaction}") >> wait(incomingTransaction) >> dequeue1ReadyTransactions()
        )

    incomingTransactions
      .evalMap(optimizeWithReadyPool)
      .filter(_.nonEmpty)
      .map(_.toList.toSet)
      .map(L1Edge)
      .evalTap(edge => logger.debug(s"[$node][Created Edge] ${edge}"))
  }

  private def wait(transaction: L1Transaction): IO[Unit] =
    waitingTransactions.modify { pool =>
      val updated = pool.updatedWith(transaction.src) { waitingChain =>
        waitingChain
          .map(_ ++ TreeSet(transaction))
          .orElse(Some(TreeSet(transaction)))
      }
      (updated, ())
    }

  private def isParentAccepted(transaction: L1Transaction): IO[Boolean] =
    lastAccepted.get.map { accepted =>
      lazy val isVeryFirstTransaction = transaction.parentHash == ""
      lazy val isParentHashAccepted = accepted.get(transaction.src).contains(transaction.parentHash)
      isVeryFirstTransaction || isParentHashAccepted
    }

  private def dequeue1ReadyTransactions(): IO[TreeSet[L1Transaction]] = readyTransactions.modify { ready =>
    ready
      .foldRight((ready, TreeSet.empty)) {
        case ((address, enqueued), (updatedReady, dequeued)) =>
          val newUpdatedReady =
            updatedReady.updated(address, if (enqueued.nonEmpty) enqueued.drop(1) else TreeSet.empty)
          val newDequeued: TreeSet[L1Transaction] = enqueued.headOption.map(dequeued + _).getOrElse(TreeSet.empty)
          (newUpdatedReady, newDequeued)
      }
  }

  def ready(acceptedTransaction: L1Transaction): IO[Unit] =
    for {
      // TODO: Check if tx exists in waitingTransactions first
      _ <- lastAccepted.modify { txs =>
        (txs.updated(acceptedTransaction.src, acceptedTransaction.hash), ())
      }

      unlockedTx <- waitingTransactions.modify { pool =>
        pool
          .get(acceptedTransaction.src)
          .flatMap(_.find(_.parentHash == acceptedTransaction.hash))
          .map { readyTx =>
            val updatedWaitingTransactions = pool.updatedWith(readyTx.src)(_.map(_.filterNot(_.hash == readyTx.hash)))
            (updatedWaitingTransactions, Some(readyTx))
          }
          .getOrElse(pool, None)
      }

      _ <- unlockedTx.fold(IO.unit) { tx =>
        readyTransactions.modify { readyPool =>
          (readyPool.updatedWith(tx.src)(_.map(_ + tx).orElse(Some(TreeSet(tx)))), ())
        }
      }

    } yield ()
}

object L1EdgeFactory {
  implicit val ordinalNumberOrdering: Ordering[L1Transaction] = (x: L1Transaction, y: L1Transaction) =>
    implicitly[Ordering[Int]].compare(x.ordinal, y.ordinal)

  def apply(node: String): L1EdgeFactory = new L1EdgeFactory(node: String)(ordinalNumberOrdering)
}