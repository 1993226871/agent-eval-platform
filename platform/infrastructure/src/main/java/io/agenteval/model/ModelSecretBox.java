package io.agenteval.model;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** Deployment key is supplied out of band; database ciphertext is bound to the profile ID. */
public final class ModelSecretBox {
    private final byte[] key;
    private final SecureRandom random=new SecureRandom();
    public ModelSecretBox(byte[] key) {
        if(key!=null && key.length!=32) throw new IllegalArgumentException("Model encryption key must be 256 bits");
        this.key=key==null?null:key.clone();
    }
    public String encrypt(UUID profile,String plaintext) {
        if(key==null) throw new ModelUnavailableException();
        try {
            byte[] nonce=new byte[12]; random.nextBytes(nonce);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(profile.toString().getBytes(StandardCharsets.UTF_8));
            return "v1:"+Base64.getEncoder().encodeToString(nonce)+":"+Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception failure) { throw new ModelUnavailableException(); }
    }
    public String decrypt(UUID profile,String ciphertext) {
        if(key==null) throw new ModelUnavailableException();
        try {
            var parts=ciphertext.split(":",-1); if(parts.length!=3 || !parts[0].equals("v1")) throw new IllegalArgumentException();
            byte[] nonce=Base64.getDecoder().decode(parts[1]); if(nonce.length!=12) throw new IllegalArgumentException();
            var cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(profile.toString().getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])),StandardCharsets.UTF_8);
        } catch(Exception failure) { throw new ModelUnavailableException(); }
    }
}
