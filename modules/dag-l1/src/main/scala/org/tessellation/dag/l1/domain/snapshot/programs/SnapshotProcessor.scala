package org.tessellation.dag.l1.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.{Applicative, MonadThrow}

import scala.util.control.NoStackTrace

import org.tessellation.dag.domain.block.{BlockReference, DAGBlock}
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.block.BlockStorage.MajorityReconciliationData
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor._
import org.tessellation.dag.l1.domain.snapshot.storage.LastGlobalSnapshotStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.snapshot._
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.types.numeric.NonNegLong

object SnapshotProcessor {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F],
    lastGlobalSnapshotStorage: LastGlobalSnapshotStorage[F],
    transactionStorage: TransactionStorage[F]
  ): SnapshotProcessor[F] =
    new SnapshotProcessor[F](addressStorage, blockStorage, lastGlobalSnapshotStorage, transactionStorage) {}

  sealed trait Alignment
  case class AlignedAtNewOrdinal(
    toMarkMajority: Set[(ProofsHash, NonNegLong)],
    tipsToDeprecate: Set[ProofsHash],
    tipsToRemove: Set[ProofsHash]
  ) extends Alignment
  case class AlignedAtNewHeight(
    toMarkMajority: Set[(ProofsHash, NonNegLong)],
    obsoleteToRemove: Set[ProofsHash],
    tipsToDeprecate: Set[ProofsHash],
    tipsToRemove: Set[ProofsHash]
  ) extends Alignment
  case class DownloadNeeded(
    toAdd: Set[(Hashed[DAGBlock], NonNegLong)],
    obsoleteToRemove: Set[ProofsHash],
    activeTips: Set[ActiveTip],
    deprecatedTips: Set[BlockReference]
  ) extends Alignment
  case class RedownloadNeeded(
    toAdd: Set[(Hashed[DAGBlock], NonNegLong)],
    toMarkMajority: Set[(ProofsHash, NonNegLong)],
    acceptedToRemove: Set[ProofsHash],
    obsoleteToRemove: Set[ProofsHash],
    toReset: Set[ProofsHash],
    tipsToDeprecate: Set[ProofsHash],
    tipsToRemove: Set[ProofsHash]
  ) extends Alignment

  sealed trait SnapshotProcessingResult
  case object UnexpectedFailure extends SnapshotProcessingResult
  case class Aligned(
    reference: GlobalSnapshotReference,
    removedObsoleteBlocks: Set[ProofsHash]
  ) extends SnapshotProcessingResult
  case class DownloadPerformed(
    reference: GlobalSnapshotReference,
    addedBlock: Set[ProofsHash],
    removedObsoleteBlocks: Set[ProofsHash]
  ) extends SnapshotProcessingResult
  case class RedownloadPerformed(
    reference: GlobalSnapshotReference,
    addedBlocks: Set[ProofsHash],
    removedBlocks: Set[ProofsHash],
    removedObsoleteBlocks: Set[ProofsHash]
  ) extends SnapshotProcessingResult

  sealed trait SnapshotProcessingError extends NoStackTrace
  case class UnexpectedCaseCheckingAlignment(
    lastHeight: Height,
    lastSubHeight: SubHeight,
    lastOrdinal: SnapshotOrdinal,
    processingHeight: Height,
    processingSubHeight: SubHeight,
    processingOrdinal: SnapshotOrdinal
  ) extends SnapshotProcessingError {
    override def getMessage: String =
      s"Unexpected case during global snapshot processing! Last: (height: $lastHeight, subHeight: $lastSubHeight, ordinal: $lastOrdinal) processing: (height: $processingHeight, subHeight:$processingSubHeight, ordinal: $processingOrdinal)."
  }
  case class TipsGotMisaligned(deprecatedToAdd: Set[ProofsHash], activeToDeprecate: Set[ProofsHash])
      extends SnapshotProcessingError {
    override def getMessage: String =
      s"Tips got misaligned! Check the implementation! deprecatedToAdd -> $deprecatedToAdd not equal activeToDeprecate -> $activeToDeprecate"
  }
}

sealed abstract class SnapshotProcessor[F[_]: Async: KryoSerializer: SecurityProvider] private (
  addressStorage: AddressStorage[F],
  blockStorage: BlockStorage[F],
  lastGlobalSnapshotStorage: LastGlobalSnapshotStorage[F],
  transactionStorage: TransactionStorage[F]
) {

  def process(globalSnapshot: Hashed[GlobalSnapshot]): F[SnapshotProcessingResult] =
    checkAlignment(globalSnapshot).flatMap {
      case AlignedAtNewOrdinal(toMarkMajority, tipsToDeprecate, tipsToRemove) =>
        blockStorage
          .adjustToMajority(
            toMarkMajority = toMarkMajority,
            tipsToDeprecate = tipsToDeprecate,
            tipsToRemove = tipsToRemove
          )
          .flatMap(_ => lastGlobalSnapshotStorage.set(globalSnapshot))
          .map { _ =>
            Aligned(
              GlobalSnapshotReference.fromHashedGlobalSnapshot(globalSnapshot),
              Set.empty
            )
          }

      case AlignedAtNewHeight(toMarkMajority, obsoleteToRemove, tipsToDeprecate, tipsToRemove) =>
        blockStorage
          .adjustToMajority(
            toMarkMajority = toMarkMajority,
            obsoleteToRemove = obsoleteToRemove,
            tipsToDeprecate = tipsToDeprecate,
            tipsToRemove = tipsToRemove
          )
          .flatMap(_ => lastGlobalSnapshotStorage.set(globalSnapshot))
          .map { _ =>
            Aligned(
              GlobalSnapshotReference.fromHashedGlobalSnapshot(globalSnapshot),
              obsoleteToRemove
            )
          }

      case DownloadNeeded(toAdd, obsoleteToRemove, activeTips, deprecatedTips) =>
        val adjustToMajority: F[Unit] =
          blockStorage.adjustToMajority(
            toAdd = toAdd,
            obsoleteToRemove = obsoleteToRemove,
            activeTipsToAdd = activeTips,
            deprecatedTipsToAdd = deprecatedTips
          )

        val setBalances: F[Unit] =
          addressStorage.clean >>
            addressStorage.updateBalances(globalSnapshot.info.balances)

        val setTransactionRefs: F[Unit] =
          transactionStorage.setLastAccepted(globalSnapshot.info.lastTxRefs)

        val setInitialSnapshot: F[Unit] =
          lastGlobalSnapshotStorage.setInitial(globalSnapshot)

        adjustToMajority >>
          setBalances >>
          setTransactionRefs >>
          setInitialSnapshot.map { _ =>
            DownloadPerformed(
              GlobalSnapshotReference.fromHashedGlobalSnapshot(globalSnapshot),
              toAdd.map(_._1.proofsHash),
              obsoleteToRemove
            )
          }

      case RedownloadNeeded(
          toAdd,
          toMarkMajority,
          acceptedToRemove,
          obsoleteToRemove,
          toReset,
          tipsToDeprecate,
          tipsToRemove
          ) =>
        val adjustToMajority: F[Unit] =
          blockStorage.adjustToMajority(
            toAdd = toAdd,
            toMarkMajority = toMarkMajority,
            acceptedToRemove = acceptedToRemove,
            obsoleteToRemove = obsoleteToRemove,
            toReset = toReset,
            tipsToDeprecate = tipsToDeprecate,
            tipsToRemove = tipsToRemove
          )

        val setBalances: F[Unit] =
          addressStorage.clean >>
            addressStorage.updateBalances(globalSnapshot.info.balances)

        val setTransactionRefs: F[Unit] =
          transactionStorage.setLastAccepted(globalSnapshot.info.lastTxRefs)

        val setSnapshot: F[Unit] =
          lastGlobalSnapshotStorage.set(globalSnapshot)

        adjustToMajority >>
          setBalances >>
          setTransactionRefs >>
          setSnapshot.map { _ =>
            RedownloadPerformed(
              GlobalSnapshotReference.fromHashedGlobalSnapshot(globalSnapshot),
              toAdd.map(_._1.proofsHash),
              acceptedToRemove,
              obsoleteToRemove
            )
          }
    }

  private def checkAlignment(globalSnapshot: GlobalSnapshot): F[Alignment] =
    for {
      acceptedInMajority <- globalSnapshot.blocks.toList.traverse {
        case BlockAsActiveTip(block, usageCount) =>
          block.hashWithSignatureCheck.flatMap(_.liftTo[F]).map(b => b.proofsHash -> (b, usageCount))
      }.map(_.toMap)

      GlobalSnapshotTips(gsDeprecatedTips, gsRemainedActive) = globalSnapshot.tips

      result <- lastGlobalSnapshotStorage.get.flatMap {
        case Some(last) if last.ordinal.next == globalSnapshot.ordinal && last.height == globalSnapshot.height =>
          blockStorage.getBlocksForMajorityReconciliation(last.height, globalSnapshot.height).flatMap {
            case MajorityReconciliationData(deprecatedTips, activeTips, _, _, acceptedAbove) =>
              val onlyInMajority = acceptedInMajority -- acceptedAbove
              val toMarkMajority = acceptedInMajority.view.filterKeys(acceptedAbove.contains).mapValues(_._2)
              lazy val toAdd = onlyInMajority.values.toSet
              lazy val toReset = acceptedAbove -- toMarkMajority.keySet
              val tipsToRemove = deprecatedTips -- gsDeprecatedTips.map(_.block.hash)
              val deprecatedTipsToAdd = gsDeprecatedTips.map(_.block.hash) -- deprecatedTips
              val tipsToDeprecate = activeTips -- gsRemainedActive.map(_.block.hash)
              val areTipsAligned = deprecatedTipsToAdd == tipsToDeprecate

              if (!areTipsAligned)
                MonadThrow[F].raiseError[Alignment](TipsGotMisaligned(deprecatedTipsToAdd, tipsToDeprecate))
              else if (onlyInMajority.isEmpty)
                Applicative[F].pure[Alignment](AlignedAtNewOrdinal(toMarkMajority.toSet, tipsToDeprecate, tipsToRemove))
              else
                Applicative[F]
                  .pure[Alignment](
                    RedownloadNeeded(
                      toAdd,
                      toMarkMajority.toSet,
                      Set.empty,
                      Set.empty,
                      toReset,
                      tipsToDeprecate,
                      tipsToRemove
                    )
                  )
          }

        case Some(last)
            if last.ordinal.next == globalSnapshot.ordinal && last.height.value < globalSnapshot.height.value =>
          blockStorage.getBlocksForMajorityReconciliation(last.height, globalSnapshot.height).flatMap {
            case MajorityReconciliationData(
                deprecatedTips,
                activeTips,
                waitingInRange,
                acceptedInRange,
                acceptedAbove
                ) =>
              val acceptedLocally = acceptedInRange ++ acceptedAbove
              val onlyInMajority = acceptedInMajority -- acceptedLocally
              val toMarkMajority = acceptedInMajority.view.filterKeys(acceptedLocally.contains).mapValues(_._2)
              val acceptedToRemove = acceptedInRange -- acceptedInMajority.keySet
              lazy val toAdd = onlyInMajority.values.toSet
              lazy val toReset = acceptedLocally -- toMarkMajority.keySet -- acceptedToRemove
              val obsoleteToRemove = waitingInRange -- onlyInMajority.keySet
              val tipsToRemove = deprecatedTips -- gsDeprecatedTips.map(_.block.hash)
              val deprecatedTipsToAdd = gsDeprecatedTips.map(_.block.hash) -- deprecatedTips
              val tipsToDeprecate = activeTips -- gsRemainedActive.map(_.block.hash)
              val areTipsAligned = deprecatedTipsToAdd == tipsToDeprecate

              if (!areTipsAligned)
                MonadThrow[F].raiseError[Alignment](TipsGotMisaligned(deprecatedTipsToAdd, tipsToDeprecate))
              else if (onlyInMajority.isEmpty && acceptedToRemove.isEmpty)
                Applicative[F].pure[Alignment](
                  AlignedAtNewHeight(toMarkMajority.toSet, obsoleteToRemove, tipsToDeprecate, tipsToRemove)
                )
              else
                Applicative[F].pure[Alignment](
                  RedownloadNeeded(
                    toAdd,
                    toMarkMajority.toSet,
                    acceptedToRemove,
                    obsoleteToRemove,
                    toReset,
                    tipsToDeprecate,
                    tipsToRemove
                  )
                )
          }

        case Some(last) =>
          MonadThrow[F].raiseError[Alignment](
            UnexpectedCaseCheckingAlignment(
              last.height,
              last.subHeight,
              last.ordinal,
              globalSnapshot.height,
              globalSnapshot.subHeight,
              globalSnapshot.ordinal
            )
          )

        case None =>
          blockStorage.getBlocksForMajorityReconciliation(Height.MinValue, globalSnapshot.height).flatMap {
            case MajorityReconciliationData(_, _, waitingInRange, _, _) =>
              val obsoleteToRemove = waitingInRange -- acceptedInMajority.keySet -- gsRemainedActive
                .map(_.block.hash) -- gsDeprecatedTips.map(_.block.hash)

              Applicative[F].pure[Alignment](
                DownloadNeeded(
                  acceptedInMajority.values.toSet,
                  obsoleteToRemove,
                  gsRemainedActive,
                  gsDeprecatedTips.map(_.block)
                )
              )
          }
      }
    } yield result
}