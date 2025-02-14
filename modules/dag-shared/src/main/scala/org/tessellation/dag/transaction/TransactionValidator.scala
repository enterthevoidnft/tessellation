package org.tessellation.dag.transaction

import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.dag.transaction.TransactionValidator.TransactionValidationErrorOr
import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.SecurityProvider
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.SignedValidator.SignedValidationError
import org.tessellation.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._

trait TransactionValidator[F[_]] {

  def validate(signedTransaction: Signed[Transaction]): F[TransactionValidationErrorOr[Signed[Transaction]]]

}

object TransactionValidator {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    signedValidator: SignedValidator[F]
  ): TransactionValidator[F] =
    new TransactionValidator[F] {

      def validate(
        signedTransaction: Signed[Transaction]
      ): F[TransactionValidationErrorOr[Signed[Transaction]]] =
        for {
          signaturesV <- signedValidator
            .validateSignatures(signedTransaction)
            .map(_.errorMap[TransactionValidationError](InvalidSigned))
          srcAddressSignatureV <- validateSourceAddressSignature(signedTransaction)
          differentSrcAndDstV = validateDifferentSourceAndDestinationAddress(signedTransaction)
        } yield
          signaturesV
            .productR(srcAddressSignatureV)
            .productR(differentSrcAndDstV)

      private def validateSourceAddressSignature(
        signedTx: Signed[Transaction]
      ): F[TransactionValidationErrorOr[Signed[Transaction]]] =
        signedTx.proofs.existsM { proof =>
          proof.id.hex.toPublicKey.map { signerPk =>
            signerPk.toAddress =!= signedTx.value.source
          }
        }.ifM(
          NotSignedBySourceAddressOwner
            .asInstanceOf[TransactionValidationError]
            .invalidNec[Signed[Transaction]]
            .pure[F],
          signedTx.validNec[TransactionValidationError].pure[F]
        )

      private def validateDifferentSourceAndDestinationAddress(
        signedTx: Signed[Transaction]
      ): TransactionValidationErrorOr[Signed[Transaction]] =
        if (signedTx.source =!= signedTx.destination)
          signedTx.validNec[TransactionValidationError]
        else
          SameSourceAndDestinationAddress(signedTx.source).invalidNec[Signed[Transaction]]
    }

  @derive(eqv, show)
  sealed trait TransactionValidationError
  case class InvalidSigned(error: SignedValidationError) extends TransactionValidationError
  case object NotSignedBySourceAddressOwner extends TransactionValidationError
  case class SameSourceAndDestinationAddress(address: Address) extends TransactionValidationError

  type TransactionValidationErrorOr[A] = ValidatedNec[TransactionValidationError, A]
}
