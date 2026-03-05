package com.apicatalog.cel.witness.verifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.apicatalog.multibase.Multibase;
import com.apicatalog.multicodec.codec.KeyCodec;

class VerifierTest {

    @Test
    void testEc256Jcs() {
        var isValid = Verifier.getVerifier("ecdsa-jcs-2019").verify(
                RawKeyImporter.loadNistCompressed(
                        KeyCodec.P256_PUBLIC_KEY.decode(
                                Multibase.BASE_58_BTC.decode("zDnaer5PFEcdcb2pibj8q6BtPLhUAsF85UAAaf4HzPP4hWzNY")),
                        "secp256r1",
                        "SHA256withECDSA"),
                Multibase.BASE_58_BTC.decode(
                        "z381yXZWPi84qKPBQTz8ugg23v5Qd6BQ1qMYfCmU36qH4N8KeCvMzxHWvpQ45n9rrXTSCaKCWQKaGoTXKY9eGBEuvDUVYuani"),
                "z5C5b1uzYJN6pDR3aWgAqUMo",
                "2026-03-01T21:15:16Z",
                "did:key:zDnaer5PFEcdcb2pibj8q6BtPLhUAsF85UAAaf4HzPP4hWzNY#zDnaer5PFEcdcb2pibj8q6BtPLhUAsF85UAAaf4HzPP4hWzNY",
                "1RLi73qEfeGU-O-tC_BGO-zuj2A-ndkrTiU5OK2APAQ");

        assertTrue(isValid);

    }

    @Test
    void testEd256Rdfc() {
        var isValid = Verifier.getVerifier("eddsa-rdfc-2022").verify(
                RawKeyImporter.loadEd25519(
                        KeyCodec.ED25519_PUBLIC_KEY.decode(
                                Multibase.BASE_58_BTC.decode("z6MkfFnKw9QwkU32VQYC6TKfAW2A6ueUjfbrFYxq9yQzoowo"))),
                Multibase.BASE_58_BTC.decode(
                        "z3WeFNBVF8uCAgPpYeBm6s6xFuV6xggySZPZCo9CahTH1vrvP5m9wp2f1AtufamCZXU6VRYfKzU1BPsYriZnZKaJV"),
                "z5C5b1uzYJN6pDR3aWgAqUMo",
                "2026-03-02T21:30:36Z",
                "did:key:z6MkfFnKw9QwkU32VQYC6TKfAW2A6ueUjfbrFYxq9yQzoowo#z6MkfFnKw9QwkU32VQYC6TKfAW2A6ueUjfbrFYxq9yQzoowo",
                "QyfGNDSOK0gkTuOEymW6zWlLJqZYjEU7TcagWmeoU1k");

        assertTrue(isValid);
    }
}
