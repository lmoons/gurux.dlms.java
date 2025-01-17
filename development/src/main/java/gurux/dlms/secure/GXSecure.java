//
// --------------------------------------------------------------------------
//  Gurux Ltd
// 
//
//
// Filename:        $HeadURL$
//
// Version:         $Revision$,
//                  $Date$
//                  $Author$
//
// Copyright (c) Gurux Ltd
//
//---------------------------------------------------------------------------
//
//  DESCRIPTION
//
// This file is a part of Gurux Device Framework.
//
// Gurux Device Framework is Open Source software; you can redistribute it
// and/or modify it under the terms of the GNU General Public License 
// as published by the Free Software Foundation; version 2 of the License.
// Gurux Device Framework is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of 
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
// See the GNU General Public License for more details.
//
// More information of Gurux products: https://www.gurux.org
//
// This code is licensed under the GNU General Public License v2. 
// Full text may be retrieved at http://www.gnu.org/licenses/gpl-2.0.txt
//---------------------------------------------------------------------------

package gurux.dlms.secure;

import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyAgreement;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import gurux.dlms.ConnectionState;
import gurux.dlms.GXByteBuffer;
import gurux.dlms.GXDLMSExceptionResponse;
import gurux.dlms.GXDLMSSettings;
import gurux.dlms.GXDLMSTranslator;
import gurux.dlms.GXICipher;
import gurux.dlms.asn.GXAsn1BitString;
import gurux.dlms.asn.GXAsn1Converter;
import gurux.dlms.asn.GXAsn1Integer;
import gurux.dlms.asn.GXAsn1Sequence;
import gurux.dlms.asn.GXPkcs8;
import gurux.dlms.asn.GXx509Certificate;
import gurux.dlms.asn.enums.KeyUsage;
import gurux.dlms.enums.Authentication;
import gurux.dlms.enums.Command;
import gurux.dlms.enums.ExceptionServiceError;
import gurux.dlms.enums.ExceptionStateError;
import gurux.dlms.enums.KeyAgreementScheme;
import gurux.dlms.enums.Security;
import gurux.dlms.internal.GXCommon;
import gurux.dlms.objects.enums.SecurityPolicy;
import gurux.dlms.objects.enums.SecuritySuite;

public final class GXSecure {
	private static final byte[] IV = { (byte) 0xA6, (byte) 0xA6, (byte) 0xA6, (byte) 0xA6, (byte) 0xA6, (byte) 0xA6,
			(byte) 0xA6, (byte) 0xA6 };

	/**
	 * Default Logger.
	 */
	private final static Logger LOGGER = Logger.getLogger(GXSecure.class.getName());

	/*
	 * Constructor.
	 */
	private GXSecure() {

	}

	private static int getBlockLength(final byte[] data) {
		if (data.length % 16 == 0) {
			return data.length;
		}
		return data.length + (16 - data.length % 16);
	}

	/*
	 * Encrypt data using AES.
	 * 
	 * @param data Encrypted data.
	 * 
	 * @param secret Secret.
	 */
	static byte[] aes1Encrypt(final byte[] data, final byte[] secret)
			throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, IllegalBlockSizeException,
			BadPaddingException, InvalidAlgorithmParameterException {
		byte[] d = new byte[getBlockLength(data)];
		byte[] s = new byte[16];
		System.arraycopy(data, 0, d, 0, data.length);
		System.arraycopy(secret, 0, s, 0, secret.length);
		IvParameterSpec ivspec = new IvParameterSpec(new byte[16]);
		SecretKeySpec keyToBeWrapped2 = new SecretKeySpec(s, "AES");
		Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, keyToBeWrapped2, ivspec);
		return cipher.doFinal(d);
	}

	/*
	 * Decrypt data using AES.
	 * 
	 * @param data Encrypted data.
	 * 
	 * @param secret Secret.
	 */
	static byte[] aes1Decrypt(final byte[] data, final byte[] secret)
			throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, IllegalBlockSizeException,
			BadPaddingException, InvalidAlgorithmParameterException {
		byte[] d = new byte[getBlockLength(data)];
		byte[] s = new byte[16];
		System.arraycopy(data, 0, d, 0, data.length);
		System.arraycopy(secret, 0, s, 0, secret.length);

		IvParameterSpec ivspec = new IvParameterSpec(new byte[16]);
		SecretKeySpec keyToBeWrapped2 = new SecretKeySpec(s, "AES");
		Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, keyToBeWrapped2, ivspec);
		return cipher.doFinal(d);
	}

	/*
	 * Encrypt data using AES RFC3394 key-wrapping.
	 * 
	 * @param data Encrypted data.
	 */
	static byte[] encryptAesKeyWrapping(final byte[] data, final byte[] kek) throws InvalidKeyException,
			NoSuchAlgorithmException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException {
		if (kek == null || kek.length % 8 != 0) {
			throw new IllegalArgumentException("Key Encrypting Key");
		}
		int n = data.length / 8;

		if ((n * 8) != data.length) {
			throw new IllegalArgumentException("Invalid data.");
		}
		byte[] block = new byte[data.length + IV.length];
		byte[] buf = new byte[8 + IV.length];

		System.arraycopy(IV, 0, block, 0, IV.length);
		System.arraycopy(data, 0, block, IV.length, data.length);
		SecretKeySpec keyToBeWrapped2 = new SecretKeySpec(kek, "AES");
		Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, keyToBeWrapped2);
		for (int j = 0; j != 6; j++) {
			for (int i = 1; i <= n; i++) {
				System.arraycopy(block, 0, buf, 0, IV.length);
				System.arraycopy(block, 8 * i, buf, IV.length, 8);
				buf = cipher.doFinal(buf);
				int t = n * j + i;
				for (int k = 1; t != 0; k++) {
					byte v = (byte) t;
					buf[IV.length - k] ^= v;
					t = t >> 8;
				}
				System.arraycopy(buf, 0, block, 0, 8);
				System.arraycopy(buf, 8, block, 8 * i, 8);
			}
		}
		return block;
	}

	/*
	 * Decrypt data using AES RFC3394 key-wrapping.
	 * 
	 * @param input Decrypted data.
	 * 
	 * @param kek KEK.
	 */
	static byte[] decryptAesKeyWrapping(final byte[] input, final byte[] kek) {
		if (kek == null || kek.length % 8 != 0) {
			throw new IllegalArgumentException("Key Encrypting Key");
		}
		int n = input.length / 8;
		if ((n * 8) != input.length) {
			throw new IllegalArgumentException("Invalid data.");
		}

		byte[] block;
		if (input.length > IV.length) {
			block = new byte[input.length - IV.length];
		} else {
			block = new byte[IV.length];
		}
		byte[] a = new byte[IV.length];
		byte[] buf = new byte[8 + IV.length];

		System.arraycopy(input, 0, a, 0, IV.length);
		System.arraycopy(input, 0 + IV.length, block, 0, input.length - IV.length);

		n = n - 1;
		if (n == 0) {
			n = 1;
		}
		try {
			SecretKeySpec keyToBeWrapped2 = new SecretKeySpec(kek, "AES");
			Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, keyToBeWrapped2);
			for (int j = 5; j >= 0; j--) {
				for (int i = n; i >= 1; i--) {
					System.arraycopy(a, 0, buf, 0, IV.length);
					System.arraycopy(block, 8 * (i - 1), buf, IV.length, 8);
					int t = n * j + i;
					for (int k = 1; t != 0; k++) {
						byte v = (byte) t;
						buf[IV.length - k] ^= v;
						t = t >> 8;
					}
					buf = cipher.doFinal(buf);
					System.arraycopy(buf, 0, a, 0, 8);
					System.arraycopy(buf, 8, block, 8 * (i - 1), 8);
				}
			}
		} catch (Exception ex) {
			throw new RuntimeException(ex.getMessage());
		}
		if (!GXCommon.compare(a, IV)) {
			throw new ArithmeticException("AES key wrapping failed.");
		}
		return block;
	}

	/*
	 * Chipher text.
	 * 
	 * @param settings DLMS settings.
	 * 
	 * @param cipher Chipher settings.
	 * 
	 * @param ic IC
	 * 
	 * @param data Text to chipher.
	 * 
	 * @param secret Secret.
	 * 
	 * @return Chiphered text.
	 */
	public static byte[] secure(final GXDLMSSettings settings, final GXICipher cipher, final long ic, final byte[] data,
			final byte[] secret) throws InvalidKeyException, NoSuchPaddingException, InvalidAlgorithmParameterException,
			IllegalBlockSizeException, BadPaddingException, SignatureException {
		try {

			byte[] d;
			if (settings.getAuthentication() == Authentication.HIGH) {
				return aes1Encrypt(data, secret);
			}
			// Get server Challenge.
			GXByteBuffer challenge = new GXByteBuffer();
			// Get shared secret
			if (settings.getAuthentication() == Authentication.HIGH_GMAC) {
				challenge.set(data);
			} else if (settings.getAuthentication() == Authentication.HIGH_SHA256) {
				challenge.set(secret);
			} else {
				challenge.set(data);
				challenge.set(secret);
			}
			d = challenge.array();
			MessageDigest md;
			switch (settings.getAuthentication()) {
			case HIGH_MD5:
				md = MessageDigest.getInstance("MD5");
				d = md.digest(d);
				break;
			case HIGH_SHA1:
				md = MessageDigest.getInstance("SHA-1");
				d = md.digest(d);
				break;
			case HIGH_SHA256:
				md = MessageDigest.getInstance("SHA-256");
				d = md.digest(d);
				break;
			case HIGH_GMAC:
				// SC is always Security.Authentication.
				AesGcmParameter p = new AesGcmParameter(0, Security.AUTHENTICATION, cipher.getSecuritySuite(), ic,
						secret, cipher.getBlockCipherKey(), cipher.getAuthenticationKey());
				p.setType(CountType.TAG);
				challenge.clear();
				challenge.setUInt8(
						Security.AUTHENTICATION.getValue() | settings.getCipher().getSecuritySuite().getValue());
				challenge.setUInt32(p.getInvocationCounter());
				challenge.set(encryptAesGcm(true, p, d));
				d = challenge.array();
				break;
			case HIGH_ECDSA:
				Signature sig = Signature.getInstance("SHA256withECDSA");
				if (cipher.getSigningKeyPair() == null) {
					throw new IllegalArgumentException("SigningKeyPair is empty.");
				}
				sig.initSign(cipher.getSigningKeyPair().getPrivate());
				GXByteBuffer bb = new GXByteBuffer();
				bb.set(settings.getCipher().getSystemTitle());
				bb.set(settings.getSourceSystemTitle());
				if (settings.isServer()) {
					bb.set(settings.getCtoSChallenge());
					bb.set(settings.getStoCChallenge());
				} else {
					bb.set(settings.getStoCChallenge());
					bb.set(settings.getCtoSChallenge());
				}
				sig.update(bb.array());
				d = sig.sign();
				GXAsn1Sequence seq = (GXAsn1Sequence) GXAsn1Converter.fromByteArray(d);
				bb.size(0);
				bb.set(((GXAsn1Integer) seq.get(0)).getByteArray());
				if (bb.size() != 32) {
					bb.move(1, 0, 32);
				}
				bb.set(((GXAsn1Integer) seq.get(1)).getByteArray());
				if (bb.size() != 64) {
					bb.move(33, 32, 32);
				}
				d = bb.array();
				break;
			default:
			}
			return d;
		} catch (NoSuchAlgorithmException ex) {
			throw new RuntimeException(ex.getMessage());
		}
	}

	/*
	 * Generates challenge.
	 * 
	 * @param authentication Used authentication.
	 * 
	 * @return Generated challenge.
	 */
	public static byte[] generateChallenge(final Authentication authentication) {
		Random r = new Random();
		// Random challenge is 8 to 64 bytes.
		// Texas Instruments accepts only 16 byte long challenge.
		// For this reason challenge size is 16 bytes at the moment.
		int len = 16;
		// int len = r.nextInt(57) + 8;
		byte[] result = new byte[len];
		for (int pos = 0; pos != len; ++pos) {
			result[pos] = (byte) r.nextInt(0x7A);
		}
		return result;
	}

	/*
	 * Generate KDF.
	 * 
	 * @param hashAlg Hash Algorithm. (SHA-256 or SHA-384 )
	 * 
	 * @param z Shared Secret.
	 * 
	 * @param keyDataLen Key data length in bits.
	 * 
	 * @param algorithmID Algorithm ID.
	 * 
	 * @param partyUInfo Sender system title.
	 * 
	 * @param partyVInfo Receiver system title.
	 * 
	 * @param suppPubInfo Not used in DLMS.
	 * 
	 * @param suppPrivInfo Not used in DLMS.
	 * 
	 * @return Generated KDF.
	 */
	public static byte[] generateKDF(final String hashAlg, final byte[] z, final int keyDataLen,
			final byte[] algorithmID, final byte[] partyUInfo, final byte[] partyVInfo, final byte[] suppPubInfo,
			final byte[] suppPrivInfo) {
		GXByteBuffer bb = new GXByteBuffer();
		bb.set(algorithmID);
		bb.set(partyUInfo);
		bb.set(partyVInfo);
		if (suppPubInfo != null) {
			bb.set(suppPubInfo);
		}
		if (suppPrivInfo != null) {
			bb.set(suppPrivInfo);
		}
		return generateKDF(hashAlg, z, keyDataLen, bb.array());
	}

	/*
	 * Generate KDF.
	 * 
	 * @param hashAlg Hash Algorithm. (SHA-256 or SHA-384 )
	 * 
	 * @param z Shared Secret.
	 * 
	 * @param keyDataLen Key data length in bits.
	 * 
	 * @param otherInfo OtherInfo
	 * 
	 * @return Generated KDF.
	 */
	private static byte[] generateKDF(final String hashAlg, final byte[] z, final int keyDataLen,
			final byte[] otherInfo) {
		byte[] key = new byte[keyDataLen / 8];
		try {
			MessageDigest md = MessageDigest.getInstance(hashAlg);
			int hashLen = md.getDigestLength();
			int cnt = key.length / hashLen;
			byte[] v = new byte[4];
			for (int pos = 1; pos <= cnt; pos++) {
				md.reset();
				getBytes(v, pos);
				md.update(v);
				md.update(z);
				md.update(otherInfo);
				byte[] hash = md.digest();
				if (pos < cnt) {
					System.arraycopy(hash, 0, key, hashLen * (pos - 1), hashLen);
				} else {
					if (key.length % hashLen == 0) {
						System.arraycopy(hash, 0, key, hashLen * (pos - 1), hashLen);
					} else {
						System.arraycopy(hash, 0, key, hashLen * (pos - 1), key.length % hashLen);
					}
				}
			}
			return key;
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage());
		}
	}

	/*
	 * Convert int to byte array.
	 * 
	 * @param buff Buffer where data is saved.
	 * 
	 * @param value Integer value.
	 */
	private static void getBytes(final byte[] buff, final int value) {
		buff[0] = (byte) (value >>> 24);
		buff[1] = (byte) ((value >>> 16) & 0xFF);
		buff[2] = (byte) ((value >>> 8) & 0xFF);
		buff[3] = (byte) (value & 0xFF);
	}

	/**
	 * Get Ephemeral Public Key Signature.
	 * 
	 * @param keyId        Key ID.
	 * @param ephemeralKey Ephemeral key.
	 * @return Ephemeral Public Key Signature.
	 */
	public static byte[] getEphemeralPublicKeyData(final int keyId, final PublicKey ephemeralKey) {
		GXAsn1BitString tmp = (GXAsn1BitString) ((GXAsn1Sequence) GXAsn1Converter
				.fromByteArray(ephemeralKey.getEncoded())).get(1);

		// Ephemeral public key client
		GXByteBuffer epk = new GXByteBuffer(tmp.getValue());
		// First byte is 4 in Java and that is not used. We can override it.
		epk.getData()[0] = (byte) keyId;
		return epk.array();
	}

	/*
	 * Get Ephemeral Public Key Signature.
	 * 
	 * @param keyId Key ID.
	 * 
	 * @param ephemeralKey Ephemeral key.
	 * 
	 * @param signKey Private Key.
	 * 
	 * @return Ephemeral Public Key Signature.
	 */
	public static byte[] getEphemeralPublicKeySignature(final int keyId, final PublicKey ephemeralKey,
			final PrivateKey signKey) throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
		byte[] epk = getEphemeralPublicKeyData(keyId, ephemeralKey);
		// Add ephemeral public key signature.
		Signature instance = Signature.getInstance("SHA256withECDSA");
		instance.initSign(signKey);
		instance.update(epk);
		byte[] sign = instance.sign();
		GXAsn1Sequence tmp2 = (GXAsn1Sequence) GXAsn1Converter.fromByteArray(sign);
		LOGGER.log(Level.FINEST, "{0}", GXCommon.toHex(sign));
		GXByteBuffer data = new GXByteBuffer(64);
		// Truncate to 64 bytes. Remove zeros from the begin.
		byte[] arr = ((GXAsn1Integer) tmp2.get(0)).getByteArray();
		if (arr.length < 32) {
			GXByteBuffer bb = new GXByteBuffer();
			bb.size(32 - arr.length);
			bb.set(arr);
			arr = bb.array();
		}
		data.set(arr, arr.length - 32, 32);
		arr = ((GXAsn1Integer) tmp2.get(1)).getByteArray();
		data.set(arr, arr.length - 32, 32);
		return data.array();
	}

	/*
	 * Validate ephemeral public key signature.
	 * 
	 * @param data Data to validate.
	 * 
	 * @param sign Sign.
	 * 
	 * @param publicSigningKey Public Signing key from other party.
	 * 
	 * @return Is verify succeeded.
	 */
	public static boolean validateEphemeralPublicKeySignature(final byte[] data, final byte[] sign,
			final PublicKey publicSigningKey) throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {

		GXAsn1Integer a = new GXAsn1Integer(sign, 0, 32);
		GXAsn1Integer b = new GXAsn1Integer(sign, 32, 32);
		GXAsn1Sequence s = new GXAsn1Sequence();
		s.add(a);
		s.add(b);
		byte[] tmp = GXAsn1Converter.toByteArray(s);
		Signature instance = Signature.getInstance("SHA256withECDSA");
		instance.initVerify(publicSigningKey);
		instance.update(data);
		boolean v = instance.verify(tmp);
		if (!v) {
			LOGGER.log(Level.FINEST, "Data: {0}", GXCommon.toHex(data));
			LOGGER.log(Level.FINEST, "Sign: {0}", GXCommon.toHex(sign));
		}
		return v;
	}

	private static SecretKeySpec getKey(byte[] pass) throws NoSuchAlgorithmException {
		final MessageDigest sha = MessageDigest.getInstance("SHA-256");

		byte[] key = sha.digest(pass);
		// use only first 128 bit (16 bytes). By default Java only supports AES
		// 128 bit key sizes for encryption.
		// Updated jvm policies are required for 256 bit.
		key = Arrays.copyOf(key, 16);
		return new SecretKeySpec(key, "AES");
	}

	private static Cipher getCipher(final AesGcmParameter p, final boolean encrypt) throws NoSuchAlgorithmException,
			NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException {
		GXByteBuffer iv = new GXByteBuffer();
		iv.set(p.getSystemTitle());
		iv.setUInt32(p.getInvocationCounter());
		SecretKeySpec eks = new SecretKeySpec(p.getBlockCipherKey(), "AES");
		Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
		int mode;
		if (encrypt) {
			mode = Cipher.ENCRYPT_MODE;
		} else {
			mode = Cipher.DECRYPT_MODE;
		}
		c.init(mode, eks, new GCMParameterSpec(12 * 8, iv.array()));
		return c;
	}

	private static byte[] countTag(final Cipher c, final AesGcmParameter p, final byte[] data)
			throws IllegalBlockSizeException, BadPaddingException {
		GXByteBuffer data2 = new GXByteBuffer();
		data2.setUInt8(p.getSecurity().getValue() | p.getSecuritySuite().getValue());
		data2.set(p.getAuthenticationKey());
		if (data != null) {
			data2.set(data);
		}
		c.updateAAD(data2.array());
		byte[] tmp = c.doFinal();
		// DLMS Tag is only 12 bytes.
		byte[] tag = new byte[12];
		System.arraycopy(tmp, 0, tag, 0, 12);
		return tag;
	}

	private static byte[] encrypt(final Cipher c, final byte[] data)
			throws IllegalBlockSizeException, BadPaddingException {
		byte[] es = c.doFinal(data);
		// Remove tag.
		byte[] tmp = new byte[es.length - 12];
		System.arraycopy(es, 0, tmp, 0, tmp.length);
		return tmp;
	}

	public static byte[] encryptAesGcm(final boolean encrypt, final AesGcmParameter p, final byte[] data)
			throws IllegalBlockSizeException, BadPaddingException, InvalidKeyException, NoSuchAlgorithmException,
			NoSuchPaddingException, InvalidAlgorithmParameterException {
		Cipher c = getCipher(p, encrypt || p.getSecurity() != Security.AUTHENTICATION_ENCRYPTION);
		if (LOGGER.isLoggable(Level.FINEST)) {
			LOGGER.log(Level.FINEST, "Authentication key: {0}", GXCommon.toHex(p.getAuthenticationKey()));
			LOGGER.log(Level.FINEST, "Block cipher key key: {0}", GXCommon.toHex(p.getBlockCipherKey()));
		}

		GXByteBuffer bb = new GXByteBuffer(10 + data.length);
		if (encrypt) {
			if (p.getType() == CountType.PACKET) {
				bb.setUInt8(p.getSecurity().getValue() | p.getSecuritySuite().getValue());
				bb.setUInt32(p.getInvocationCounter());
			}
			if (p.getSecurity() == Security.AUTHENTICATION) {
				byte[] tmp = countTag(c, p, data);
				if (p.getType() == CountType.PACKET) {
					bb.set(data);
				}
				bb.set(tmp);
			} else if (p.getSecurity() == Security.ENCRYPTION) {
				bb.set(encrypt(c, data));
			} else if (p.getSecurity() == Security.AUTHENTICATION_ENCRYPTION) {
				GXByteBuffer data2 = new GXByteBuffer();
				data2.setUInt8(p.getSecurity().getValue() | p.getSecuritySuite().getValue());
				data2.set(p.getAuthenticationKey());
				c.updateAAD(data2.array());
				bb.set(c.doFinal(data));
			}
			if (p.getType() == CountType.PACKET) {
				GXByteBuffer bb2 = new GXByteBuffer(5 + bb.size());
				bb2.setUInt8(p.getTag());
				if (p.getTag() == Command.GENERAL_GLO_CIPHERING || p.getTag() == Command.GENERAL_DED_CIPHERING
						|| p.getTag() == Command.DATA_NOTIFICATION) {
					GXCommon.setObjectCount(p.getSystemTitle().length, bb2);
					bb2.set(p.getSystemTitle());
				}
				GXCommon.setObjectCount(bb.size(), bb2);
				bb2.set(bb);
				return bb2.array();
			}
			return bb.array();
		}
		if (p.getSecurity() == Security.ENCRYPTION) {
			byte[] tmp2 = c.doFinal(data);
			// Remove tag.
			byte[] tmp = new byte[tmp2.length - 12];
			System.arraycopy(tmp2, 0, tmp, 0, tmp.length);
			return tmp;
		}
		if (p.getSecurity() == Security.AUTHENTICATION) {
			// Get tag.
			byte[] received = new byte[12];
			System.arraycopy(data, data.length - 12, received, 0, 12);
			GXByteBuffer data2 = new GXByteBuffer();
			data2.set(data);
			data2.size(data2.size() - 12);
			byte[] counted = countTag(c, p, data2.array());
			if (!GXCommon.compare(counted, received)) {
				throw new javax.crypto.AEADBadTagException();
			}
			return data2.array();
		}
		GXByteBuffer data2 = new GXByteBuffer();
		data2.setUInt8(p.getSecurity().getValue() | p.getSecuritySuite().getValue());
		data2.set(p.getAuthenticationKey());
		c.updateAAD(data2.array());
		try {
			return c.doFinal(data);
		} catch (Exception ex) {
			if (p.getXml() == null) {
				Logger.getLogger(GXSecure.class.getName()).log(Level.SEVERE,
						"Decrypt failed. Invalid authentication tag.");
				throw ex;
			}
			if (ex instanceof javax.crypto.AEADBadTagException) {
				p.getXml().appendComment("Authentication tag is invalid.");
				c = getCipher(p, true);
				// Remove tag and encrypt the data.
				bb.set(data, 0, data.length - 12);
				byte[] tmp2 = c.doFinal(bb.array());
				// Remove tag.
				byte[] tmp = new byte[tmp2.length - 12];
				System.arraycopy(tmp2, 0, tmp, 0, tmp.length);
				return tmp;
			} else {
				throw ex;
			}
		}
	}

	private static int securityPolicy2Security(final GXDLMSSettings settings,
			final java.util.Set<SecurityPolicy> value) {
		int tmp = SecurityPolicy.toInteger(value);
		if (tmp < 4) {
			return tmp;
		}
		tmp = 0;
		if ((settings.getConnected() & ConnectionState.DLMS) != 0
				&& value.contains(SecurityPolicy.DIGITALLY_SIGNED_REQUEST)) {
			tmp = Security.DIGITALLY_SIGNED.getValue();
		}
		if (value.contains(SecurityPolicy.AUTHENTICATED_REQUEST)) {
			tmp = Security.AUTHENTICATION.getValue();
		}
		if (value.contains(SecurityPolicy.ENCRYPTED_REQUEST)) {
			tmp |= Security.ENCRYPTION.getValue();
		}
		return tmp >> 4;
	}

	/*
	 * Decrypts data using Aes Gcm.
	 * 
	 * @param c Cipher settings.
	 * 
	 * @param p GMAC Parameter.
	 * 
	 * @return Decrypted data.
	 */
	static byte[] decryptAesGcm(final GXICipher c, final AesGcmParameter p, final GXByteBuffer data)
			throws InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException,
			InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {
		try {
			if (data == null || data.size() - data.position() < 2) {
				throw new IllegalArgumentException("cryptedData");
			}
			byte[] tmp;
			int len, cmd = data.getUInt8();
			switch (cmd) {
			case Command.GENERAL_GLO_CIPHERING:
			case Command.GENERAL_DED_CIPHERING:
				len = GXCommon.getObjectCount(data);
				if (len != 0) {
					byte[] title = new byte[len];
					data.get(title);
					p.setSystemTitle(title);
				}
				break;
			case Command.GENERAL_CIPHERING:
			case Command.GLO_INITIATE_REQUEST:
			case Command.GLO_INITIATE_RESPONSE:
			case Command.GLO_READ_REQUEST:
			case Command.GLO_READ_RESPONSE:
			case Command.GLO_WRITE_REQUEST:
			case Command.GLO_WRITE_RESPONSE:
			case Command.GLO_GET_REQUEST:
			case Command.GLO_GET_RESPONSE:
			case Command.GLO_SET_REQUEST:
			case Command.GLO_SET_RESPONSE:
			case Command.GLO_METHOD_REQUEST:
			case Command.GLO_METHOD_RESPONSE:
			case Command.GLO_EVENT_NOTIFICATION_REQUEST:
			case Command.DED_INITIATE_REQUEST:
			case Command.DED_INITIATE_RESPONSE:
			case Command.DED_GET_REQUEST:
			case Command.DED_GET_RESPONSE:
			case Command.DED_SET_REQUEST:
			case Command.DED_SET_RESPONSE:
			case Command.DED_METHOD_REQUEST:
			case Command.DED_METHOD_RESPONSE:
			case Command.DED_EVENT_NOTIFICATION:
			case Command.DED_READ_REQUEST:
			case Command.DED_READ_RESPONSE:
			case Command.DED_WRITE_REQUEST:
			case Command.DED_WRITE_RESPONSE:
			case Command.GLO_CONFIRMED_SERVICE_ERROR:
			case Command.DED_CONFIRMED_SERVICE_ERROR:
				break;
			default:
				throw new IllegalArgumentException("cryptedData");
			}
			int value = 0;
			PrivateKey key = null;
			PublicKey pub = null;
			GXByteBuffer transactionId = null;
			if (cmd == Command.GENERAL_CIPHERING) {
				transactionId = new GXByteBuffer();
				len = GXCommon.getObjectCount(data);
				GXCommon.setObjectCount(len, transactionId);
				transactionId.set(data, len);
				len = GXCommon.getObjectCount(data);
				tmp = new byte[len];
				if (len != 0) {
					data.get(tmp);
					p.setSystemTitle(tmp);
				}
				p.getSettings().setSourceSystemTitle(tmp);
				p.setSystemTitle(tmp);
				len = GXCommon.getObjectCount(data);
				tmp = new byte[len];
				data.get(tmp);
				if (p.getXml() == null && Arrays.compare(tmp, c.getSystemTitle()) != 0) {
					throw new IllegalArgumentException("Invalid recipient system title.");
				}
				p.setRecipientSystemTitle(tmp);
				// Get date time.
				len = GXCommon.getObjectCount(data);
				if (len != 0) {
					tmp = new byte[len];
					data.get(tmp);
					p.setDateTime(tmp);
				}
				// other-information
				len = data.getUInt8();
				if (len != 0) {
					tmp = new byte[len];
					data.get(tmp);
					p.setOtherInformation(tmp);
				}
				// KeyInfo OPTIONAL
				// len =
				data.getUInt8();
				// AgreedKey CHOICE tag.
				data.getUInt8();
				// key-parameters
				// len =
				data.getUInt8();
				value = data.getUInt8();
				p.setKeyParameters(value);
				if (value == 1) {
					p.getSettings().getCipher().setSecurity(Security.DIGITALLY_SIGNED);
					p.getSettings().getCipher().setKeyAgreementScheme(KeyAgreementScheme.ONE_PASS_DIFFIE_HELLMAN);
					// key-ciphered-data
					len = GXCommon.getObjectCount(data);
					GXByteBuffer bb = new GXByteBuffer();
					bb.set(data, len);
					if (p.getXml() != null) {
						p.setKeyCipheredData(bb.array());
						// Find key agreement key using subject.
						String subject = GXAsn1Converter.systemTitleToSubject(p.getSettings().getSourceSystemTitle());
						for (Map.Entry<GXPkcs8, GXx509Certificate> it : p.getSettings().getKeys()) {
							if (it.getValue().getKeyUsage().contains(KeyUsage.KEY_AGREEMENT)
									&& it.getValue().getSubject().contains(subject)) {
								key = it.getKey().getPrivateKey();
								break;
							}
						}
					} else {
						if (p.getSettings().getCipher().getKeyAgreementKeyPair() == null) {
							Logger.getLogger(GXSecure.class.getName()).log(Level.WARNING,
									"KeyAgreementKeyPair is null.");
						} else if (p.getSettings().getCipher().getKeyAgreementKeyPair().getPrivate() == null) {
							Logger.getLogger(GXSecure.class.getName()).log(Level.WARNING,
									"KeyAgreementKeyPair private key is null.");
						}
						key = p.getSettings().getCipher().getKeyAgreementKeyPair().getPrivate();
					}
					if (key != null) {
						// Get Ephemeral public key.
						int keySize = len / 2;
						pub = GXAsn1Converter.getPublicKey(bb.subArray(0, keySize));
					}
				} else if (value == 2) {
					p.getSettings().getCipher().setSecurity(Security.DIGITALLY_SIGNED);
					p.getSettings().getCipher().setKeyAgreementScheme(KeyAgreementScheme.STATIC_UNIFIED_MODEL);
					len = GXCommon.getObjectCount(data);
					if (len != 0) {
						throw new IllegalArgumentException("Invalid key parameters");
					}
					if (p.getXml() != null) {
						// Find key agreement key using subject.
						String subject = GXAsn1Converter.systemTitleToSubject(p.getSystemTitle());
						for (Map.Entry<GXPkcs8, GXx509Certificate> it : p.getSettings().getKeys()) {
							if (it.getValue().getKeyUsage().contains(KeyUsage.KEY_AGREEMENT)
									&& it.getValue().getSubject().contains(subject)) {
								key = it.getKey().getPrivateKey();
								break;
							}
						}
						if (key != null) {
							// Find key agreement key using subject.
							subject = GXAsn1Converter.systemTitleToSubject(p.getSettings().getSourceSystemTitle());
							for (Map.Entry<GXPkcs8, GXx509Certificate> it : p.getSettings().getKeys()) {
								if (it.getValue().getKeyUsage().contains(KeyUsage.KEY_AGREEMENT)
										&& it.getValue().getSubject().contains(subject)) {
									pub = it.getValue().getPublicKey();
									break;
								}
							}
						}
					} else {
						if (p.getSettings().getCipher().getKeyAgreementKeyPair() != null) {
							key = p.getSettings().getCipher().getKeyAgreementKeyPair().getPrivate();
							pub = p.getSettings().getCipher().getKeyAgreementKeyPair().getPublic();
						}
					}
				} else {
					throw new IllegalArgumentException("key-parameters");
				}
			}
			len = GXCommon.getObjectCount(data);
			if (len < data.available()) {
				throw new IllegalArgumentException("Not enought data.");
			}
			p.setCipheredContent(data.remaining());
			byte sc = (byte) data.getUInt8();
			Security security = Security.forValue(sc & 0x30);
			java.util.Set<SecurityPolicy> sp = SecurityPolicy.forValue(sc >> 4);
			if (p.getXml() == null && !c.getSecurityPolicy().isEmpty() && p.getSettings().isServer()
					&& securityPolicy2Security(p.getSettings(), c.getSecurityPolicy()) != SecurityPolicy
							.toInteger(sp)) {
				String error = "Invalid security policy. Expected: " + c.getSecurityPolicy() + ". Actual:" + sp;
				Logger.getLogger(GXSecure.class.getName()).log(Level.SEVERE, error);
				throw new GXDLMSExceptionResponse(ExceptionStateError.SERVICE_NOT_ALLOWED,
						ExceptionServiceError.DECIPHERING_ERROR, null);
			}
			SecuritySuite ss = SecuritySuite.forValue(sc & 0x3);
			if (p.getXml() == null && p.getSettings().isServer()
					&& ss != p.getSettings().getCipher().getSecuritySuite()) {
				String error = "Invalid security Suite. Expected: " + c.getSecuritySuite() + ". Actual:" + ss;
				Logger.getLogger(GXSecure.class.getName()).log(Level.SEVERE, error);
				throw new GXDLMSExceptionResponse(ExceptionStateError.SERVICE_NOT_ALLOWED,
						ExceptionServiceError.DECIPHERING_ERROR, null);
			}
			p.setSecuritySuite(ss);
			if ((sc & 0x80) != 0) {
				Logger.getLogger(GXSecure.class.getName()).log(Level.FINEST, "Compression is used.");
			}
			if ((sc & 0x40) != 0) {
				Logger.getLogger(GXSecure.class.getName()).log(Level.WARNING, "Error: Key_Set is used.");
				throw new GXDLMSExceptionResponse(ExceptionStateError.SERVICE_NOT_ALLOWED,
						ExceptionServiceError.DECIPHERING_ERROR, null);
			}
			if ((sc & 0x20) != 0) {
				Logger.getLogger(GXSecure.class.getName()).log(Level.FINEST, "Encryption is applied.");
			}

			if (p.getXml() == null && c.getSecuritySuite() != ss) {
				String error = "Invalid security suite. Expected: " + c.getSecuritySuite() + ". Actual:" + ss;
				Logger.getLogger(GXSecure.class.getName()).log(Level.SEVERE, error);
				throw new RuntimeException(error);
			}
			p.setSecurity(security);
			if (key != null) {
				p.getSettings().getCipher().setSecurity(Security.DIGITALLY_SIGNED);
				if (value == 1) {
					// Client generates shared secret.
					KeyAgreement ka = KeyAgreement.getInstance("ECDH");
					ka.init(key);
					ka.doPhase(pub, true);
					byte[] z = ka.generateSecret();
					Logger.getLogger(GXSecure.class.getName()).log(Level.FINEST, "Private agreement key {0}",
							GXCommon.toHex(GXAsn1Converter.rawValue(key), true));
					Logger.getLogger(GXSecure.class.getName()).log(Level.FINEST, "Public ephemeral key: {0}",
							GXCommon.toHex(GXAsn1Converter.rawValue(pub), true));
					Logger.getLogger(GXSecure.class.getName()).log(Level.FINEST, "Shared secret: {0}",
							GXCommon.toHex(z, true));
					byte[] algID = GXCommon.hexToBytes("60857405080300"); // AES-GCM-128
					GXByteBuffer kdf = new GXByteBuffer();
					kdf.set(GXSecure.generateKDF("SHA-256", z, 256, algID, p.getSystemTitle(),
							p.getRecipientSystemTitle(), null, null));
					Logger.getLogger(GXSecure.class.getName()).log(Level.FINEST, "KDF {0}", kdf);
					p.setBlockCipherKey(kdf.subArray(0, 16));
				} else if (value == 2) {
					// Client generates shared secret.
					KeyAgreement ka = KeyAgreement.getInstance("ECDH");
					ka.init(p.getSettings().getCipher().getKeyAgreementKeyPair().getPrivate());
					ka.doPhase(pub, true);
					byte[] z = ka.generateSecret();
					Logger.getLogger(GXSecure.class.getName()).log(Level.FINEST, "Private Agreement key: {0}",
							GXDLMSTranslator.toHex(GXAsn1Converter
									.rawValue(p.getSettings().getCipher().getKeyAgreementKeyPair().getPrivate())));
					Logger.getLogger(GXSecure.class.getName()).log(Level.FINEST, "Public Agreement key: {0}",
							GXDLMSTranslator.toHex(GXAsn1Converter.rawValue(pub)));
					Logger.getLogger(GXSecure.class.getName()).log(Level.FINEST, "Shared secret {0}",
							GXCommon.toHex(z, true));
					byte[] algID = GXCommon.hexToBytes("60857405080300"); // AES-GCM-128
					GXByteBuffer kdf = new GXByteBuffer();
					kdf.set(GXSecure.generateKDF("SHA-256", z, 256, algID, p.getSystemTitle(), transactionId.array(),
							p.getRecipientSystemTitle(), null));

					Logger.getLogger(GXSecure.class.getName()).log(Level.FINEST, "KDF {0}", kdf);
					Logger.getLogger(GXSecure.class.getName()).log(Level.FINEST, "Authentication key {0}",
							GXDLMSTranslator.toHex(p.getAuthenticationKey()));
					// Get Ephemeral signing key.
					p.setBlockCipherKey(kdf.subArray(0, 16));
				} else {
					throw new RuntimeException("Invalid Key-id value.");
				}
			}
			long invocationCounter = data.getUInt32();
			if (value == 1 || value == 2) {
				BigInteger b = transactionId.getUInt64(1);
				if (p.getSettings().getInvocationCounter() != null
						&& p.getSettings().getInvocationCounter().getValue() instanceof Number) {
					if (b.longValue() < ((Number) p.getSettings().getInvocationCounter().getValue()).longValue()) {
						throw new GXDLMSExceptionResponse(ExceptionStateError.SERVICE_NOT_ALLOWED,
								ExceptionServiceError.INVOCATION_COUNTER_ERROR,
								p.getSettings().getInvocationCounter().getValue());
					}
					// Update IC value.
					p.getSettings().getInvocationCounter().setValue(b);
				}
			} else if (p.getSettings().getInvocationCounter() != null
					&& p.getSettings().getInvocationCounter().getValue() instanceof Number) {
				if (invocationCounter < ((Number) p.getSettings().getInvocationCounter().getValue()).longValue()) {
					throw new GXDLMSExceptionResponse(ExceptionStateError.SERVICE_NOT_ALLOWED,
							ExceptionServiceError.INVOCATION_COUNTER_ERROR,
							p.getSettings().getInvocationCounter().getValue());
				}
				// Update IC value.
				p.getSettings().getInvocationCounter().setValue(invocationCounter);
			}
			p.setInvocationCounter(invocationCounter);
			byte[] encryptedData;
			if (security == Security.AUTHENTICATION) {
				try {
					encryptedData = encryptAesGcm(false, p, data.remaining());
				} catch (AEADBadTagException e) {
					Logger.getLogger(GXSecure.class.getName()).log(Level.SEVERE,
							"Decrypt failed. Invalid authentication tag.");
					encryptedData = null;
					if (p.getXml() == null) {
						throw e;
					} else {
						p.getXml().appendComment("Decrypt failed. Invalid authentication tag.");
					}
				}
				return encryptedData;
			}
			encryptedData = encryptAesGcm(false, p, data.remaining());
			return encryptedData;
		} catch (AEADBadTagException ex) {
			throw ex;
		} catch (Exception ex) {
			Logger.getLogger(GXSecure.class.getName()).log(Level.SEVERE, ex.getMessage());
			throw new GXDLMSExceptionResponse(ExceptionStateError.SERVICE_NOT_ALLOWED,
					ExceptionServiceError.DECIPHERING_ERROR, null);
		}
	}
}
