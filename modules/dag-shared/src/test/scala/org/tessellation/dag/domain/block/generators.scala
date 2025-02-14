package org.tessellation.dag.domain.block

import cats.data.{NonEmptyList, NonEmptySet}

import org.tessellation.dag.domain.block.{BlockReference, DAGBlock}
import org.tessellation.schema.generators._
import org.tessellation.security.signature.Signed

import org.scalacheck.{Arbitrary, Gen}

object generators {

  val blockReferencesGen: Gen[NonEmptyList[BlockReference]] =
    Gen.nonEmptyListOf(Arbitrary.arbitrary[BlockReference]).map(NonEmptyList.fromListUnsafe(_))

  val dagBlockGen: Gen[DAGBlock] =
    for {
      blockReferences <- blockReferencesGen
      signedTxn <- signedTransactionGen
    } yield DAGBlock(blockReferences, NonEmptySet.of(signedTxn))

  val signedDAGBlockGen: Gen[Signed[DAGBlock]] = signedOf(dagBlockGen)

}
