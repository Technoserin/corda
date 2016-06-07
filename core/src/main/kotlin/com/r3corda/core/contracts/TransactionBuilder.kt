package com.r3corda.core.contracts

import com.r3corda.core.crypto.DigitalSignature
import com.r3corda.core.crypto.Party
import com.r3corda.core.crypto.SecureHash
import com.r3corda.core.crypto.signWithECDSA
import com.r3corda.core.serialization.serialize
import java.security.KeyPair
import java.security.PublicKey
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * A TransactionBuilder is a transaction class that's mutable (unlike the others which are all immutable). It is
 * intended to be passed around contracts that may edit it by adding new states/commands or modifying the existing set.
 * Then once the states and commands are right, this class can be used as a holding bucket to gather signatures from
 * multiple parties.
 */
class TransactionBuilder(private val inputs: MutableList<StateRef> = arrayListOf(),
                         private val attachments: MutableList<SecureHash> = arrayListOf(),
                         private val outputs: MutableList<TransactionState<ContractState>> = arrayListOf(),
                         private val commands: MutableList<Command> = arrayListOf(),
                         private val type: TransactionType = TransactionType.Business()) {

    val time: TimestampCommand? get() = commands.mapNotNull { it.value as? TimestampCommand }.singleOrNull()

    /**
     * Places a [TimestampCommand] in this transaction, removing any existing command if there is one.
     * The command requires a signature from the Notary service, which acts as a Timestamp Authority.
     * The signature can be obtained using [NotaryProtocol].
     *
     * The window of time in which the final timestamp may lie is defined as [time] +/- [timeTolerance].
     * If you want a non-symmetrical time window you must add the command via [addCommand] yourself. The tolerance
     * should be chosen such that your code can finish building the transaction and sending it to the TSA within that
     * window of time, taking into account factors such as network latency. Transactions being built by a group of
     * collaborating parties may therefore require a higher time tolerance than a transaction being built by a single
     * node.
     */
    fun setTime(time: Instant, authority: Party, timeTolerance: Duration) {
        check(currentSigs.isEmpty()) { "Cannot change timestamp after signing" }
        commands.removeAll { it.value is TimestampCommand }
        addCommand(TimestampCommand(time, timeTolerance), authority.owningKey)
    }

    /** A more convenient way to add items to this transaction that calls the add* methods for you based on type */
    fun withItems(vararg items: Any): TransactionBuilder {
        for (t in items) {
            when (t) {
                is StateAndRef<*> -> addInputState(t)
                is TransactionState<*> -> addOutputState(t)
                is Command -> addCommand(t)
                else -> throw IllegalArgumentException("Wrong argument type: ${t.javaClass}")
            }
        }
        return this
    }

    /** The signatures that have been collected so far - might be incomplete! */
    private val currentSigs = arrayListOf<DigitalSignature.WithKey>()

    fun signWith(key: KeyPair) {
        check(currentSigs.none { it.by == key.public }) { "This partial transaction was already signed by ${key.public}" }
        val data = toWireTransaction().serialize()
        addSignatureUnchecked(key.signWithECDSA(data.bits))
    }

    /**
     * Checks that the given signature matches one of the commands and that it is a correct signature over the tx, then
     * adds it.
     *
     * @throws SignatureException if the signature didn't match the transaction contents
     * @throws IllegalArgumentException if the signature key doesn't appear in any command.
     */
    fun checkAndAddSignature(sig: DigitalSignature.WithKey) {
        checkSignature(sig)
        addSignatureUnchecked(sig)
    }

    /**
     * Checks that the given signature matches one of the commands and that it is a correct signature over the tx.
     *
     * @throws SignatureException if the signature didn't match the transaction contents
     * @throws IllegalArgumentException if the signature key doesn't appear in any command.
     */
    fun checkSignature(sig: DigitalSignature.WithKey) {
        require(commands.any { it.signers.contains(sig.by) }) { "Signature key doesn't match any command" }
        sig.verifyWithECDSA(toWireTransaction().serialized)
    }

    /** Adds the signature directly to the transaction, without checking it for validity. */
    fun addSignatureUnchecked(sig: DigitalSignature.WithKey) {
        currentSigs.add(sig)
    }

    fun toWireTransaction() = WireTransaction(ArrayList(inputs), ArrayList(attachments),
            ArrayList(outputs), ArrayList(commands), type)

    fun toSignedTransaction(checkSufficientSignatures: Boolean = true): SignedTransaction {
        if (checkSufficientSignatures) {
            val gotKeys = currentSigs.map { it.by }.toSet()
            for (command in commands) {
                if (!gotKeys.containsAll(command.signers))
                    throw IllegalStateException("Missing signatures on the transaction for a ${command.value.javaClass.canonicalName} command")
            }
        }
        return SignedTransaction(toWireTransaction().serialize(), ArrayList(currentSigs))
    }

    fun addInputState(stateAndRef: StateAndRef<*>) {
        check(currentSigs.isEmpty())

        val notaryKey = stateAndRef.state.notary.owningKey
        if (commands.none { it.signers.contains(notaryKey) }) {
            commands.add(Command(NotaryCommand(), notaryKey))
        }

        inputs.add(stateAndRef.ref)
    }

    fun addAttachment(attachment: Attachment) {
        check(currentSigs.isEmpty())
        attachments.add(attachment.id)
    }

    fun addOutputState(state: TransactionState<*>) {
        check(currentSigs.isEmpty())
        outputs.add(state)
    }

    fun addCommand(arg: Command) {
        check(currentSigs.isEmpty())
        // We should probably merge the lists of pubkeys for identical commands here.
        commands.add(arg)
    }

    fun addCommand(data: CommandData, vararg keys: PublicKey) = addCommand(Command(data, listOf(*keys)))
    fun addCommand(data: CommandData, keys: List<PublicKey>) = addCommand(Command(data, keys))

    // Accessors that yield immutable snapshots.
    fun inputStates(): List<StateRef> = ArrayList(inputs)

    fun outputStates(): List<TransactionState<*>> = ArrayList(outputs)
    fun commands(): List<Command> = ArrayList(commands)
    fun attachments(): List<SecureHash> = ArrayList(attachments)
}