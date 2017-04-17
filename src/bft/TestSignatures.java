/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft;

import bft.BFTNode;
import bftsmart.tom.MessageContext;
import bftsmart.tom.core.messages.TOMMessage;
import com.google.protobuf.ByteString;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringWriter;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1OutputStream;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.hyperledger.fabric.protos.common.Common;
import org.hyperledger.fabric.protos.msp.Identities;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.security.CryptoPrimitives;

/**
 *
 * @author joao
 */
public class TestSignatures {
    
    private static CryptoPrimitives crypto;
    private static final int NUM_BATCHES = 200;
    private static final int BATCH_SIZE = 10;
    private static final int ENV_SIZE = 1024;
    private static final int NUM_BLOCKS = 100;
    
    private static final String Mspid = "DEFAULT";

    private static Random rand;
    private static ExecutorService executor = null;
    private static PrivateKey privKey = null;
    private static byte[] serializedCert = null;
    private static Identities.SerializedIdentity ident;
    
    //measurements
    private static long sigsMeasurementStartTime = -1;
    private static int interval = 10000;
    private static int countSigs = 0;
    
    public static void main(String[] args) throws CryptoException, InvalidArgumentException, NoSuchAlgorithmException, NoSuchProviderException, IOException {
        
        TestSignatures.crypto = new CryptoPrimitives();
        TestSignatures.crypto.init();
        TestSignatures.rand = new Random(System.nanoTime());
        //TestSignatures.executor = Executors.newFixedThreadPool(Integer.parseInt(args[0]));
        TestSignatures.executor = Executors.newCachedThreadPool();
        TestSignatures.privKey = getPemPrivateKey(args[1]);
        parseCertificate(args[2]);
        TestSignatures.ident = getSerializedIdentity();
        
        int interval = Integer.parseInt(args[3]);
        
        //Generate pool of batches
        System.out.print("Generating " + NUM_BATCHES + " batches with " + BATCH_SIZE + " envelopes each... ");
        byte[][][] batches = new byte[NUM_BATCHES][BATCH_SIZE][];
        for (int i = 0; i < NUM_BATCHES; i++) {
            
            for (int j = 0; j < BATCH_SIZE; j++) {
                
                batches[i][j] = new byte[ENV_SIZE];
                
                rand.nextBytes(batches[i][j]);
                
                
            }
        }
        
        System.out.println(" done!");
        
        System.out.print("Generating batch pool of " + NUM_BLOCKS + " blocks... ");
        
        Common.Block[] blocks = new Common.Block[NUM_BLOCKS];
        for (int i = 0; i < NUM_BLOCKS; i++) {
            
            byte[] dummyDigest = new byte[rand.nextInt(1000)];
                    
            dummyDigest = TestSignatures.crypto.hash(dummyDigest);
            
            
            blocks[i] = createNextBlock(i, dummyDigest, batches[rand.nextInt(batches.length)]);
        }
        
        System.out.println(" done!");
        
        System.out.println("Generating signatures with a pool of " + NUM_BLOCKS + " blocks... ");
        
        while (true) {
            
            if (interval > 0) {
                
                try {
                    Thread.sleep(0, interval);
                } catch (InterruptedException ex) {
                    Logger.getLogger(TestSignatures.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
            
            TestSignatures.executor.execute(new SignerThread(blocks[rand.nextInt(NUM_BLOCKS)]));
        }
        
        
    }

    private static void parseCertificate(String filename) throws IOException {
                
            BufferedReader br = new BufferedReader(new FileReader(filename));
            PEMParser pp = new PEMParser(br);
            X509CertificateHolder certificate = (X509CertificateHolder) pp.readObject();

            PemObject pemObj = (new PemObject("", certificate.getEncoded()));

            StringWriter strWriter = new StringWriter();
            PemWriter writer = new PemWriter(strWriter);
            writer.writeObject(pemObj);
            
            writer.close();
            strWriter.close();
            
            TestSignatures.serializedCert = strWriter.toString().getBytes();
            
            
    }
    private static Identities.SerializedIdentity getSerializedIdentity() {
        
                Identities.SerializedIdentity.Builder ident = Identities.SerializedIdentity.newBuilder();
                ident.setMspid(Mspid);
                ident.setIdBytes(ByteString.copyFrom(serializedCert));
                return ident.build();
    }
    
    private static PrivateKey getPemPrivateKey(String filename) throws IOException {
        
        
        BufferedReader br = new BufferedReader(new FileReader(filename));
        //Security.addProvider(new BouncyCastleProvider());
        PEMParser pp = new PEMParser(br);
        PEMKeyPair pemKeyPair = (PEMKeyPair) pp.readObject();
                
        KeyPair kp = new JcaPEMKeyConverter().getKeyPair(pemKeyPair);
        pp.close();
        br.close();
                        
        return kp.getPrivate();
        //samlResponse.sign(Signature.getInstance("SHA1withRSA").toString(), kp.getPrivate(), certs);
                
    }
    
    private static Common.Block createNextBlock(long number, byte[] previousHash, byte[][] envs) throws NoSuchAlgorithmException, NoSuchProviderException {
        
        //initialize
        Common.BlockHeader.Builder blockHeaderBuilder = Common.BlockHeader.newBuilder();
        Common.BlockData.Builder blockDataBuilder = Common.BlockData.newBuilder();
        Common.BlockMetadata.Builder blockMetadataBuilder = Common.BlockMetadata.newBuilder();
        Common.Block.Builder blockBuilder = Common.Block.newBuilder();
                
        //create header
        blockHeaderBuilder.setNumber(number);
        blockHeaderBuilder.setPreviousHash(ByteString.copyFrom(previousHash));
        blockHeaderBuilder.setDataHash(ByteString.copyFrom(crypto.hash(concatenate(envs))));
        
        //create metadata
        int numIndexes = Common.BlockMetadataIndex.values().length;
        for (int i = 0; i < numIndexes; i++) blockMetadataBuilder.addMetadata(ByteString.EMPTY);
        
        //create data
        for (int i = 0; i < envs.length; i++)
            blockDataBuilder.addData(ByteString.copyFrom(envs[i]));

        //crete block
        blockBuilder.setHeader(blockHeaderBuilder.build());
        blockBuilder.setMetadata(blockMetadataBuilder.build());
        blockBuilder.setData(blockDataBuilder.build());
        
        return blockBuilder.build();
    }

    private static byte[] concatenate(byte[][] bytes) {

        int totalLength = 0;
        for (byte[] b : bytes) {
            if (b != null) {
                totalLength += b.length;
            }
        }

        byte[] concat = new byte[totalLength];
        int last = 0;

        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != null) {
                for (int j = 0; j < bytes[i].length; j++) {
                    concat[last + j] = bytes[i][j];
                }
                
                last += bytes[i].length;
            }

        }

        return concat;
    }

    private static class SignerThread implements Runnable {

        private Common.Block block;
        
        MessageDigest digestEngine;
        Signature signEngine;

        SignerThread(Common.Block block) {

            this.block = block;
            
        }

        private byte[] encodeBlockHeaderASN1(Common.BlockHeader header) throws IOException {

            // encode the header in ASN1 format
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ASN1OutputStream asnos = new ASN1OutputStream(bos);

            asnos.writeObject(new ASN1Integer((int) header.getNumber()));
            //asnos.writeObject(new DERInteger((int) header.getNumber()));
            asnos.writeObject(new DEROctetString(header.getPreviousHash().toByteArray()));
            asnos.writeObject(new DEROctetString(header.getDataHash().toByteArray()));
            asnos.flush();
            bos.flush();
            asnos.close();
            bos.close();

            byte[] buffer = bos.toByteArray();

            //Add golang idiocracies
            byte[] bytes = new byte[buffer.length+2];
            bytes[0] = 48; // no idea what this means, but golang's encoding uses it
            bytes[1] = (byte) buffer.length; // length of the rest of the octet string, also used by golang
            for (int i = 0; i < buffer.length; i++) { // concatenate
                bytes[i+2] = buffer[i];
            }

            return bytes;
        }
        
        private Common.Metadata createMetadataSignature(byte[] creator, byte[] nonce, byte[] plaintext, Common.BlockHeader blockHeader) throws NoSuchAlgorithmException, NoSuchProviderException, InvalidKeyException, SignatureException, IOException, CryptoException {
        
            Common.Metadata.Builder metadataBuilder = Common.Metadata.newBuilder();
            Common.MetadataSignature.Builder metadataSignatureBuilder = Common.MetadataSignature.newBuilder();
            Common.SignatureHeader.Builder signatureHeaderBuilder = Common.SignatureHeader.newBuilder();

            signatureHeaderBuilder.setCreator(ByteString.copyFrom(creator));
            signatureHeaderBuilder.setNonce(ByteString.copyFrom(nonce));

            Common.SignatureHeader sigHeader = signatureHeaderBuilder.build();

            metadataSignatureBuilder.setSignatureHeader(sigHeader.toByteString());

            byte[][] concat  = {plaintext, sigHeader.toByteString().toByteArray(), encodeBlockHeaderASN1(blockHeader)};

            //byte[] sig = sign(concatenate(concat));
            byte[] sig = crypto.ecdsaSignToBytes(privKey, concatenate(concat));

            //parseSig(sig);

            metadataSignatureBuilder.setSignature(ByteString.copyFrom(sig));

            metadataBuilder.setValue((plaintext != null ? ByteString.copyFrom(plaintext) : ByteString.EMPTY));
            metadataBuilder.addSignatures(metadataSignatureBuilder);

            return metadataBuilder.build();
        }
        
        @Override
        public void run() {

            try {
                
                if (sigsMeasurementStartTime == -1) {
                    sigsMeasurementStartTime = System.currentTimeMillis();
                }
                
                //create nonce
                byte[] nonces = new byte[rand.nextInt(10)];
                rand.nextBytes(nonces);
                
                //create signatures
                Common.Metadata blockSig = createMetadataSignature(ident.toByteArray(), nonces, null, this.block.getHeader());
                

                countSigs++;

                if (countSigs % interval == 0) {

                    float tp = (float) (interval * 1000 / (float) (System.currentTimeMillis() - sigsMeasurementStartTime));
                    System.out.println("Throughput = " + tp + " sigs/sec");
                    sigsMeasurementStartTime = System.currentTimeMillis();

                }
                
                byte[] dummyConf = {0, 0, 0, 0, 0, 0, 0, 1}; //TODO: find a way to implement the check that is done in the golang code
                Common.Metadata configSig = createMetadataSignature(ident.toByteArray(), nonces, dummyConf, this.block.getHeader());

                countSigs++;

                if (countSigs % interval == 0) {

                    float tp = (float) (interval * 1000 / (float) (System.currentTimeMillis() - sigsMeasurementStartTime));
                    System.out.println("Throughput = " + tp + " sigs/sec");
                    sigsMeasurementStartTime = System.currentTimeMillis();

                }
                

            } catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidKeyException | SignatureException | IOException | CryptoException ex) {
                Logger.getLogger(BFTNode.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

    }
}
