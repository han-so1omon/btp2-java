/*
 * Copyright 2023 ICON Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package foundation.icon.btp.bmv.eth2;

import foundation.icon.btp.lib.BMV;
import foundation.icon.btp.lib.BMVStatus;
import foundation.icon.btp.lib.BTPAddress;
import foundation.icon.score.util.Logger;
import foundation.icon.score.util.StringUtil;
import score.Address;
import score.Context;
import score.VarDB;
import score.annotation.External;
import score.annotation.Optional;
import scorex.util.ArrayList;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

public class BTPMessageVerifier implements BMV {
    private static final Logger logger = Logger.getLogger(BTPMessageVerifier.class);
    private final VarDB<BMVProperties> propertiesDB = Context.newVarDB("properties", BMVProperties.class);
    private final String eventSignature = "Message(string,uint256,bytes)";
    private final byte[] eventSignatureTopic = Context.hash("keccak-256", eventSignature.getBytes());

    public BTPMessageVerifier(
            @Optional String srcNetworkID,
            @Optional byte[] genesisValidatorsHash,
            @Optional byte[] syncCommittee,
            @Optional Address bmc,
            @Optional byte[] finalizedHeader,
            @Optional BigInteger seq
    ) {
        if (srcNetworkID == null && genesisValidatorsHash == null && syncCommittee == null && bmc == null && finalizedHeader == null && seq.signum() == 0) return;
        var properties = getProperties();
        if (srcNetworkID != null) properties.setSrcNetworkID(srcNetworkID.getBytes());
        if (bmc != null) properties.setBmc(bmc);
        if (genesisValidatorsHash != null) properties.setGenesisValidatorsHash(genesisValidatorsHash);
        if (syncCommittee != null) properties.setCurrentSyncCommittee(syncCommittee);
        if (finalizedHeader != null) properties.setFinalizedHeader(LightClientHeader.deserialize(finalizedHeader));
        if (seq.signum() == -1) throw BMVException.unknown("invalid seq. sequence must >= 0");
        var lastMsgSeq = properties.getLastMsgSeq();
        if (lastMsgSeq == null || seq.signum() == 1) properties.setLastMsgSeq(seq);
        if (properties.getLastMsgSlot() == null) properties.setLastMsgSlot(BigInteger.ZERO);
        propertiesDB.set(properties);
    }

    @External
    public void setSequence(BigInteger seq) {
        if (!Context.getCaller().equals(Context.getOwner())) throw BMVException.unknown("only owner can call this method");
        if (seq.signum() < 0) throw BMVException.unknown("invalid seq. sequence must >= 0");
        var properties = getProperties();
        properties.setLastMsgSeq(seq);
        propertiesDB.set(properties);
    }

    @External
    public byte[][] handleRelayMessage(String _bmc, String _prev, BigInteger _seq, byte[] _msg) {
        logger.println("handleRelayMessage, msg : ", StringUtil.toString(_msg));
        BTPAddress curAddr = BTPAddress.valueOf(_bmc);
        BTPAddress prevAddr = BTPAddress.valueOf(_prev);
        checkAccessible(curAddr, prevAddr);
        RelayMessage relayMessages = RelayMessage.fromBytes(_msg);
        RelayMessage.TypePrefixedMessage[] typePrefixedMessages = relayMessages.getMessages();
        BlockProof blockProof = null;
        List<byte[]> msgList = new ArrayList<>();
        for (RelayMessage.TypePrefixedMessage message : typePrefixedMessages) {
            Object msg = message.getMessage();
            if (msg instanceof BlockUpdate) {
                logger.println("handleRelayMessage, blockUpdate : " + msg);
                processBlockUpdate((BlockUpdate) msg);
            } else if (msg instanceof BlockProof) {
                logger.println("handleRelayMessage, blockProof : " + msg);
                blockProof = (BlockProof) msg;
                processBlockProof(blockProof);
            } else if (msg instanceof MessageProof) {
                logger.println("handleRelayMessage, MessageProof : " + msg);
                var msgs = processMessageProof((MessageProof) msg, blockProof);
                msgList.addAll(msgs);
            }
        }
        var retSize = msgList.size();
        var ret = new byte[retSize][];
        for (int i = 0; i < retSize; i ++)
            ret[i] = msgList.get(i);
        return ret;
    }

    @External(readonly = true)
    public BMVStatus getStatus() {
        var properties = getProperties();
        BMVStatus s = new BMVStatus();
        var finalizedHeaderSlot = properties.getFinalizedHeader().getBeacon().getSlot();
        s.setHeight(finalizedHeaderSlot.longValue());
        s.setExtra(new BMVStatusExtra(
                properties.getLastMsgSeq(),
                properties.getLastMsgSlot()).toBytes());
        return s;
    }

    @External(readonly = true)
    public String getVersion() {
        return "0.2.0";
    }

    BMVProperties getProperties() {
        return propertiesDB.getOrDefault(BMVProperties.DEFAULT);
    }

    private void processBlockUpdate(BlockUpdate blockUpdate) {
        var properties = getProperties();
        validateBlockUpdate(blockUpdate, properties);
        applyBlockUpdate(blockUpdate, properties);
    }

    private void validateBlockUpdate(BlockUpdate blockUpdate, BMVProperties properties) {
        var attestedBeacon = LightClientHeader.deserialize(blockUpdate.getAttestedHeader()).getBeacon();
        var finalizedBeacon = LightClientHeader.deserialize(blockUpdate.getFinalizedHeader()).getBeacon();
        var signatureSlot = blockUpdate.getSignatureSlot();
        var attestedSlot = attestedBeacon.getSlot();
        var finalizedSlot = finalizedBeacon.getSlot();
        logger.println("validateBlockUpdate, ", "signatureSlot : ", signatureSlot, ", attestedSlot : ", attestedSlot, ", finalizedSlot : ", finalizedSlot);
        if (signatureSlot.compareTo(attestedSlot) <= 0) throw BMVException.unknown("signature slot( + " + signatureSlot + ") must be after attested Slot(" + attestedSlot + ")");
        if (attestedSlot.compareTo(finalizedSlot) < 0) throw BMVException.unknown("attested slot (" + attestedSlot + ") must be after finalized slot(" + finalizedSlot + ")");

        var bmvFinalizedBeacon = properties.getFinalizedHeader().getBeacon();
        var bmvSlot = bmvFinalizedBeacon.getSlot();
        var bmvPeriod = Utils.computeSyncCommitteePeriod(bmvSlot);
        var signaturePeriod = Utils.computeSyncCommitteePeriod(signatureSlot);
        var isBmvPeriod = signaturePeriod.compareTo(bmvPeriod) == 0;

        if (properties.getNextSyncCommittee() != null) {
            if (!isBmvPeriod && signaturePeriod.compareTo(bmvPeriod.add(BigInteger.ONE)) != 0)
                throw BMVException.notVerifiable(bmvSlot.toString());
        } else {
            if (!isBmvPeriod)
                throw BMVException.notVerifiable(bmvSlot.toString());
        }

        blockUpdate.verifyFinalizedHeader();

        var nextSyncCommittee = blockUpdate.getNextSyncCommittee();
        if (nextSyncCommittee != null) {
            logger.println("validateBlockUpdate, ", "verify nextSyncCommittee aggregatedKey : " + StringUtil.toString(nextSyncCommittee.getAggregatePubKey()));
            blockUpdate.verifyNextSyncCommittee();
        }

        SyncCommittee syncCommittee;
        if (isBmvPeriod) {
            syncCommittee = SyncCommittee.deserialize(properties.getCurrentSyncCommittee());
        } else {
            syncCommittee = SyncCommittee.deserialize(properties.getNextSyncCommittee());
        }
        logger.println("validateBlockUpdate, ", "verify syncAggregate", syncCommittee.getAggregatePubKey());
        if (!blockUpdate.verifySyncAggregate(syncCommittee.getBlsPublicKeys(), properties.getGenesisValidatorsHash()))
            throw BMVException.unknown("invalid signature");
    }

    private void applyBlockUpdate(BlockUpdate blockUpdate, BMVProperties properties) {
        var bmvNextSyncCommittee = properties.getNextSyncCommittee();
        var bmvBeacon = properties.getFinalizedHeader().getBeacon();
        var bmvSlot = bmvBeacon.getSlot();
        var finalizedHeader = LightClientHeader.deserialize(blockUpdate.getFinalizedHeader());
        var finalizedSlot = finalizedHeader.getBeacon().getSlot();
        var bmvPeriod = Utils.computeSyncCommitteePeriod(bmvSlot);
        var finalizedPeriod = Utils.computeSyncCommitteePeriod(finalizedSlot);

        if (bmvNextSyncCommittee == null) {
            if (finalizedPeriod.compareTo(bmvPeriod) != 0) throw BMVException.unknown("invalid update period");
            logger.println("applyBlockUpdate, ", "set next sync committee");
            properties.setNextSyncCommittee(blockUpdate.getNextSyncCommittee());
        } else if (finalizedPeriod.compareTo(bmvPeriod.add(BigInteger.ONE)) == 0) {
            logger.println("applyBlockUpdate, ", "set current/next sync committee");
            properties.setCurrentSyncCommittee(properties.getNextSyncCommittee());
            properties.setNextSyncCommittee(blockUpdate.getNextSyncCommittee());
        }

        if (finalizedSlot.compareTo(bmvSlot) > 0) {
            logger.println("applyBlockUpdate, ", "set finalized header");
            properties.setFinalizedHeader(finalizedHeader);
        }
        propertiesDB.set(properties);
    }

    private void processBlockProof(BlockProof blockProof) {
        var historicalLimit = BigInteger.valueOf(8192);
        var properties = getProperties();
        var bmvBeacon = properties.getFinalizedHeader().getBeacon();
        var blockProofLightClientHeader = blockProof.getLightClientHeader();
        var blockProofBeacon = blockProofLightClientHeader.getBeacon();
        var bmvFinalizedSlot = bmvBeacon.getSlot();
        var blockProofSlot = blockProofBeacon.getSlot();
        var blockProofBeaconHashTreeRoot = blockProofBeacon.getHashTreeRoot();
        var bmvStateRoot = bmvBeacon.getStateRoot();
        var proof = blockProof.getProof();
        var proofLeaf = proof.getLeaf();
        logger.println("processBlockProof, ", "blockProofSlot : ", blockProofSlot, ", bmvFinalizedSlot : ", bmvFinalizedSlot);
        logger.println("processBlockProof, ", "bmvStateRoot : ", StringUtil.bytesToHex(bmvStateRoot), ", proof : ", proof);
        if (bmvFinalizedSlot.compareTo(blockProofSlot) < 0)
            throw BMVException.unknown(blockProofSlot.toString());
        if (blockProofSlot.add(historicalLimit).compareTo(bmvFinalizedSlot) < 0) {
            var historicalProof = blockProof.getHistoricalProof();
            logger.println("processBlockProof, ", "historicalProof : ", historicalProof);
            if (historicalProof == null)
                throw BMVException.unknown("historicalProof empty");
            if (!Arrays.equals(blockProofBeaconHashTreeRoot, historicalProof.getLeaf()))
                throw BMVException.unknown("invalid hashTree");
            SszUtils.verify(bmvStateRoot, proof);
            SszUtils.verify(proofLeaf, historicalProof);
        } else {
            if (!Arrays.equals(proofLeaf, blockProofBeaconHashTreeRoot))
                throw BMVException.unknown("invalid hashTree");
            SszUtils.verify(bmvStateRoot, proof);
        }
        propertiesDB.set(properties);
    }

    private List<byte[]> processMessageProof(MessageProof messageProof, BlockProof blockProof) {
        var properties = getProperties();
        var seq = properties.getLastMsgSeq();
        var beaconBlockHeader = blockProof.getLightClientHeader().getBeacon();
        var stateRoot = beaconBlockHeader.getStateRoot();
        var receiptRootProof = messageProof.getReceiptRootProof();
        logger.println("processMessageProof, ", "stateRoot", StringUtil.bytesToHex(stateRoot), ", receiptRootProof : ", receiptRootProof);
        SszUtils.verify(stateRoot, receiptRootProof);
        var receiptsRoot = receiptRootProof.getLeaf();
        var messageList = new ArrayList<byte[]>();
        for (ReceiptProof rp : messageProof.getReceiptProofs()) {
            logger.println("processMessageProof, ", "mpt prove", ", receiptProof key : ", StringUtil.bytesToHex(rp.getKey()));
            var value = MerklePatriciaTree.prove(receiptsRoot, rp.getKey(), rp.getProofs());
            var receipt = Receipt.fromBytes(value);
            logger.println("processMessageProof, ", "receipt : ", receipt);
            for (Log log : receipt.getLogs()) {
                var topics = log.getTopics();
                var signature = topics[0];
                if (!Arrays.equals(signature, eventSignatureTopic)) continue;
                var msgSeq = new BigInteger(topics[2]);
                seq = seq.add(BigInteger.ONE);
                if (seq.compareTo(msgSeq) != 0) throw BMVException.unknown("invalid message sequence");
                var msg = log.getMessage();
                messageList.add(msg);
            }
        }
        var cnt = messageList.size();
        if (cnt != 0) {
            properties.setLastMsgSeq(seq);
            properties.setLastMsgSlot(beaconBlockHeader.getSlot());
            propertiesDB.set(properties);
        }
        return messageList;
    }

    private void checkAccessible(BTPAddress curAddr, BTPAddress fromAddress) {
        BMVProperties properties = getProperties();
        if (!properties.getNetwork().equals(fromAddress.net())) {
            throw BMVException.unknown("invalid prev bmc");
        } else if (!Context.getCaller().equals(properties.getBmc())) {
            throw BMVException.unknown("invalid caller bmc");
        } else if (!Address.fromString(curAddr.account()).equals(properties.getBmc())) {
            throw BMVException.unknown("invalid current bmc");
        }
    }
}
