package com.wymanit.sshd;

/**
 * This class is copied from a stackoverflow question: http://stackoverflow.com/questions/3531506/using-public-key-from-authorized-keys-with-java-security
 * It is able to read and write OpenSSH public keys (the "authorized_keys" style) which we use for publickey authentication
 *
 * Created by marcusheese on 25/02/14.
 */
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.DSAParams;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.DSAPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Scanner;

import org.apache.commons.codec.binary.Base64;

public class AuthorizedKeysDecoder {
    private byte[] bytes;
    private int pos;

    public PublicKey decodePublicKey(String keyLine) throws Exception {
        bytes = null;
        pos = 0;

        // look for the Base64 encoded part of the line to decode
        // both ssh-rsa and ssh-dss begin with "AAAA" due to the length bytes
        for (String part : keyLine.split(" ")) {
            if (part.startsWith("AAAA")) {
                byte [] bytePart = part.getBytes();
                bytes = Base64.decodeBase64(bytePart);
                break;
            }
        }
        if (bytes == null) {
            throw new IllegalArgumentException("no Base64 part to decode");
        }

        String type = decodeType();
        switch (type) {
            case "ssh-rsa": {
                BigInteger e = decodeBigInt();
                BigInteger m = decodeBigInt();
                RSAPublicKeySpec spec = new RSAPublicKeySpec(m, e);
                return KeyFactory.getInstance("RSA").generatePublic(spec);
            }
            case "ssh-dss": {
                BigInteger p = decodeBigInt();
                BigInteger q = decodeBigInt();
                BigInteger g = decodeBigInt();
                BigInteger y = decodeBigInt();
                DSAPublicKeySpec spec = new DSAPublicKeySpec(y, p, q, g);
                return KeyFactory.getInstance("DSA").generatePublic(spec);
            }
            default:
                throw new IllegalArgumentException("unknown type " + type);
        }
    }

    private String decodeType() {
        int len = decodeInt();
        String type = new String(bytes, pos, len);
        pos += len;
        return type;
    }

    private int decodeInt() {
        return ((bytes[pos++] & 0xFF) << 24) | ((bytes[pos++] & 0xFF) << 16)
                | ((bytes[pos++] & 0xFF) << 8) | (bytes[pos++] & 0xFF);
    }

    private BigInteger decodeBigInt() {
        int len = decodeInt();
        byte[] bigIntBytes = new byte[len];
        System.arraycopy(bytes, pos, bigIntBytes, 0, len);
        pos += len;
        return new BigInteger(bigIntBytes);
    }

    /**
     * Encode PublicKey (DSA or RSA encoded) to authorized_keys like string
     *
     * @param publicKey DSA or RSA encoded
     * @param user username for output authorized_keys like string
     * @return authorized_keys like string
     * @throws IOException
     */
    public static String encodePublicKey(PublicKey publicKey, String user)
            throws IOException {
        String publicKeyEncoded;
        if(publicKey.getAlgorithm().equals("RSA")){
            RSAPublicKey rsaPublicKey = (RSAPublicKey) publicKey;
            ByteArrayOutputStream byteOs = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(byteOs);
            dos.writeInt("ssh-rsa".getBytes().length);
            dos.write("ssh-rsa".getBytes());
            dos.writeInt(rsaPublicKey.getPublicExponent().toByteArray().length);
            dos.write(rsaPublicKey.getPublicExponent().toByteArray());
            dos.writeInt(rsaPublicKey.getModulus().toByteArray().length);
            dos.write(rsaPublicKey.getModulus().toByteArray());
            publicKeyEncoded = new String(
                    Base64.encodeBase64(byteOs.toByteArray()));
            return "ssh-rsa " + publicKeyEncoded + " " + user;
        }
        else if(publicKey.getAlgorithm().equals("DSA")){
            DSAPublicKey dsaPublicKey = (DSAPublicKey) publicKey;
            DSAParams dsaParams = dsaPublicKey.getParams();

            ByteArrayOutputStream byteOs = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(byteOs);
            dos.writeInt("ssh-dss".getBytes().length);
            dos.write("ssh-dss".getBytes());
            dos.writeInt(dsaParams.getP().toByteArray().length);
            dos.write(dsaParams.getP().toByteArray());
            dos.writeInt(dsaParams.getQ().toByteArray().length);
            dos.write(dsaParams.getQ().toByteArray());
            dos.writeInt(dsaParams.getG().toByteArray().length);
            dos.write(dsaParams.getG().toByteArray());
            dos.writeInt(dsaPublicKey.getY().toByteArray().length);
            dos.write(dsaPublicKey.getY().toByteArray());
            publicKeyEncoded = new String(
                    Base64.encodeBase64(byteOs.toByteArray()));
            return "ssh-dss " + publicKeyEncoded + " " + user;
        }
        else{
            throw new IllegalArgumentException(
                    "Unknown public key encoding: " + publicKey.getAlgorithm());
        }
    }

    public static void main(String[] args) throws Exception {
        AuthorizedKeysDecoder decoder = new AuthorizedKeysDecoder();
        File file = new File("authorized_keys");
        Scanner scanner = new Scanner(file).useDelimiter("\n");
        while (scanner.hasNext()) {
            System.out.println(decoder.decodePublicKey(scanner.next()));
        }
        scanner.close();
    }
}