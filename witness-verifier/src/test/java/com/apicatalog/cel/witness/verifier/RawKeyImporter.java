package com.apicatalog.cel.witness.verifier;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.InvalidParameterSpecException;
import java.security.spec.NamedParameterSpec;

class RawKeyImporter {

    /**
     * Loads Ed25519 from 32-byte raw format. Note: Ed25519 raw keys are
     * Little-Endian; Java's EdECPoint expects the standard RFC 8032 representation.
     */
    public static PublicKey loadEd25519(byte[] rawBytes) {
        try {
            // Ed25519 uses the EdDSA algorithm name in Java 15+
            KeyFactory kf = KeyFactory.getInstance("EdDSA");

            // Ed25519 raw keys are essentially the Y-coordinate with a parity bit.
            // We must reverse the array because Java's BigInteger (used internally
            // by some providers) is Big-Endian, while Ed25519 is Little-Endian.
            byte[] reversed = reverse(rawBytes.clone());

            // The EdECPoint takes the BigInteger representation of the encoded point
            BigInteger y = new BigInteger(1, reversed);
            EdECPoint point = new EdECPoint(y.testBit(255), y);

            // Construct the spec for Ed25519
            NamedParameterSpec paramSpec = NamedParameterSpec.ED25519;
            EdECPublicKeySpec spec = new EdECPublicKeySpec(paramSpec, point);

            return kf.generatePublic(spec);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);

        } catch (InvalidKeySpecException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static PublicKey loadNistCompressed(byte[] compressed, String curveName, String sigAlg) {

        try {
            java.security.AlgorithmParameters params = java.security.AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec(curveName));
            ECParameterSpec ecSpec = params.getParameterSpec(ECParameterSpec.class);

            byte[] xBytes = new byte[compressed.length - 1];
            System.arraycopy(compressed, 1, xBytes, 0, xBytes.length);
            BigInteger x = new BigInteger(1, xBytes);

            BigInteger y = decompressNistY(x, compressed[0], ecSpec.getCurve());

            ECPoint point = new ECPoint(x, y);
            ECPublicKeySpec spec = new ECPublicKeySpec(point, ecSpec);
            return KeyFactory.getInstance("EC").generatePublic(spec);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);

        } catch (InvalidParameterSpecException | InvalidKeySpecException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static BigInteger decompressNistY(BigInteger x, byte prefix, EllipticCurve curve) {
        BigInteger a = curve.getA();
        BigInteger b = curve.getB();
        BigInteger p = ((java.security.spec.ECFieldFp) curve.getField()).getP();

        // y^2 = x^3 + ax + b
        BigInteger rhs = x.multiply(x).multiply(x).add(a.multiply(x)).add(b).mod(p);
        BigInteger y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p);

        if (y.testBit(0) != (prefix == 0x03)) {
            y = p.subtract(y);
        }
        return y;
    }

    private static byte[] reverse(byte[] array) {
        for (int i = 0; i < array.length / 2; i++) {
            byte temp = array[i];
            array[i] = array[array.length - 1 - i];
            array[array.length - 1 - i] = temp;
        }
        return array;
    }
}