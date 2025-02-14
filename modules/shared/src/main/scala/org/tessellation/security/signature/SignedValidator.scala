package org.tessellation.security.signature

import cats.Order
import cats.data.NonEmptySet._
import cats.data.{NonEmptySet, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ID.Id
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.SignedValidator.SignedValidationErrorOr
import org.tessellation.security.signature.signature.SignatureProof

import derevo.cats.{order, show}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.PosInt

trait SignedValidator[F[_]] {

  def validateSignatures[A <: AnyRef](
    signedBlock: Signed[A]
  ): F[SignedValidationErrorOr[Signed[A]]]

  def validateUniqueSigners[A <: AnyRef](
    signedBlock: Signed[A]
  ): SignedValidationErrorOr[Signed[A]]

  def validateMinSignatureCount[A <: AnyRef](
    signedBlock: Signed[A],
    minSignatureCount: PosInt
  ): SignedValidationErrorOr[Signed[A]]

}

object SignedValidator {

  def make[F[_]: Async: KryoSerializer: SecurityProvider]: SignedValidator[F] = new SignedValidator[F] {

    def validateSignatures[A <: AnyRef](
      signedBlock: Signed[A]
    ): F[SignedValidationErrorOr[Signed[A]]] =
      signedBlock.validProofs.map { either =>
        either
          .leftMap(InvalidSignatures)
          .toValidatedNec
          .map(_ => signedBlock)
      }

    def validateUniqueSigners[A <: AnyRef](
      signedBlock: Signed[A]
    ): SignedValidationErrorOr[Signed[A]] =
      duplicatedValues(signedBlock.proofs.map(_.id)).toNel
        .map(_.toNes)
        .map(DuplicateSigners)
        .toInvalidNec(signedBlock)

    def validateMinSignatureCount[A <: AnyRef](
      signedBlock: Signed[A],
      minSignatureCount: PosInt
    ): SignedValidationErrorOr[Signed[A]] =
      if (signedBlock.proofs.size >= minSignatureCount)
        signedBlock.validNec
      else
        NotEnoughSignatures(signedBlock.proofs.size, minSignatureCount).invalidNec

    private def duplicatedValues[B: Order](values: NonEmptySet[B]): List[B] =
      values.groupBy(identity).toNel.toList.mapFilter {
        case (value, occurrences) =>
          if (occurrences.tail.nonEmpty)
            value.some
          else
            none
      }
  }

  @derive(order, show)
  sealed trait SignedValidationError
  case class InvalidSignatures(invalidSignatures: NonEmptySet[SignatureProof]) extends SignedValidationError
  case class NotEnoughSignatures(signatureCount: Long, minSignatureCount: PosInt) extends SignedValidationError
  case class DuplicateSigners(signers: NonEmptySet[Id]) extends SignedValidationError
  case class MissingSigners(signers: NonEmptySet[Id]) extends SignedValidationError

  type SignedValidationErrorOr[A] = ValidatedNec[SignedValidationError, A]
}
