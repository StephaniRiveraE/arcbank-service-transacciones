package com.arcbank.cbs.transaccion.util;

import java.io.FileOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

public class KeyGeneratorUtil {

    public static void main(String[] args) throws Exception {
        System.out.println("Generando llaves RSA 2048 para Arcbank (Integración Switch)...");

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();

        // Guardar Llave Privada
        try (FileOutputStream fos = new FileOutputStream("arcbank_private_key.pem")) {
            fos.write("-----BEGIN PRIVATE KEY-----\n".getBytes());
            fos.write(Base64.getMimeEncoder(64, "\n".getBytes()).encode(pair.getPrivate().getEncoded()));
            fos.write("\n-----END PRIVATE KEY-----\n".getBytes());
        }

        // Guardar Llave Pública
        try (FileOutputStream fos = new FileOutputStream("arcbank_public_key.pem")) {
            fos.write("-----BEGIN PUBLIC KEY-----\n".getBytes());
            fos.write(Base64.getMimeEncoder(64, "\n".getBytes()).encode(pair.getPublic().getEncoded()));
            fos.write("\n-----END PUBLIC KEY-----\n".getBytes());
        }

        System.out.println("✅ Llaves generadas correctamente:");
        System.out.println("   - arcbank_private_key.pem (GUARDAR EN BACKEND - SECRETO)");
        System.out.println("   - arcbank_public_key.pem (ENVIAR AL SWITCH)");
    }
}
