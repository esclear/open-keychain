/*
 * Copyright (C) 2014 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2014 Vincent Breitmoser <v.breitmoser@mugenguild.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.pgp;

import org.spongycastle.asn1.ASN1ObjectIdentifier;
import org.spongycastle.asn1.nist.NISTNamedCurves;
import org.spongycastle.asn1.teletrust.TeleTrusTNamedCurves;
import org.spongycastle.bcpg.ECPublicBCPGKey;
import org.spongycastle.bcpg.SignatureSubpacketTags;
import org.spongycastle.bcpg.sig.KeyFlags;
import org.spongycastle.openpgp.PGPPublicKey;
import org.spongycastle.openpgp.PGPSignature;
import org.spongycastle.openpgp.PGPSignatureSubpacketVector;
import org.spongycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.spongycastle.util.Strings;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.util.IterableIterator;
import org.sufficientlysecure.keychain.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Iterator;

public class UncachedPublicKey {
    protected final PGPPublicKey mPublicKey;
    private Integer mCacheUsage = null;

    public UncachedPublicKey(PGPPublicKey key) {
        mPublicKey = key;
    }

    public long getKeyId() {
        return mPublicKey.getKeyID();
    }

    /** The revocation signature is NOT checked here, so this may be false! */
    public boolean isRevoked() {
        for (PGPSignature sig : new IterableIterator<PGPSignature>(
                mPublicKey.getSignaturesOfType(isMasterKey() ? PGPSignature.KEY_REVOCATION
                                                             : PGPSignature.SUBKEY_REVOCATION))) {
            return true;
        }
        return false;
    }

    public Date getCreationTime() {
        return mPublicKey.getCreationTime();
    }

    public Date getExpiryTime() {
        long seconds = mPublicKey.getValidSeconds();
        if (seconds > Integer.MAX_VALUE) {
            Log.e(Constants.TAG, "error, expiry time too large");
            return null;
        }
        if (seconds == 0) {
            // no expiry
            return null;
        }
        Date creationDate = getCreationTime();
        Calendar calendar = GregorianCalendar.getInstance();
        calendar.setTime(creationDate);
        calendar.add(Calendar.SECOND, (int) seconds);

        return calendar.getTime();
    }

    public boolean isExpired() {
        Date creationDate = mPublicKey.getCreationTime();
        Date expiryDate = mPublicKey.getValidSeconds() > 0
                ? new Date(creationDate.getTime() + mPublicKey.getValidSeconds() * 1000) : null;

        Date now = new Date();
        return creationDate.after(now) || (expiryDate != null && expiryDate.before(now));
    }

    public boolean isMasterKey() {
        return mPublicKey.isMasterKey();
    }

    public int getAlgorithm() {
        return mPublicKey.getAlgorithm();
    }

    public Integer getBitStrength() {
        if (isEC()) {
            return null;
        }
        return mPublicKey.getBitStrength();
    }

    public String getCurveOid() {
        if ( ! isEC()) {
            return null;
        }
        if ( ! (mPublicKey.getPublicKeyPacket().getKey() instanceof ECPublicBCPGKey)) {
            return null;
        }
        return ((ECPublicBCPGKey) mPublicKey.getPublicKeyPacket().getKey()).getCurveOID().getId();
    }

    /** Returns the primary user id, as indicated by the public key's self certificates.
     *
     * This is an expensive operation, since potentially a lot of certificates (and revocations)
     * have to be checked, and even then the result is NOT guaranteed to be constant through a
     * canonicalization operation.
     *
     * Returns null if there is no primary user id (as indicated by certificates)
     *
     */
    public String getPrimaryUserId() {
        byte[] found = null;
        PGPSignature foundSig = null;
        // noinspection unchecked
        for (byte[] rawUserId : new IterableIterator<byte[]>(mPublicKey.getRawUserIDs())) {
            PGPSignature revocation = null;

            @SuppressWarnings("unchecked")
            Iterator<PGPSignature> signaturesIt = mPublicKey.getSignaturesForID(rawUserId);
            // no signatures for this User ID
            if (signaturesIt == null) {
                continue;
            }

            for (PGPSignature sig : new IterableIterator<PGPSignature>(signaturesIt)) {
                try {

                    // if this is a revocation, this is not the user id
                    if (sig.getSignatureType() == PGPSignature.CERTIFICATION_REVOCATION) {
                        // make sure it's actually valid
                        sig.init(new JcaPGPContentVerifierBuilderProvider().setProvider(
                                Constants.BOUNCY_CASTLE_PROVIDER_NAME), mPublicKey);
                        if (!sig.verifyCertification(rawUserId, mPublicKey)) {
                            continue;
                        }
                        if (found != null && Arrays.equals(found, rawUserId)) {
                            found = null;
                        }
                        revocation = sig;
                        // this revocation may still be overridden by a newer cert
                        continue;
                    }

                    if (sig.getHashedSubPackets() != null && sig.getHashedSubPackets().isPrimaryUserID()) {
                        if (foundSig != null && sig.getCreationTime().before(foundSig.getCreationTime())) {
                            continue;
                        }
                        // ignore if there is a newer revocation for this user id
                        if (revocation != null && sig.getCreationTime().before(revocation.getCreationTime())) {
                            continue;
                        }
                        // make sure it's actually valid
                        sig.init(new JcaPGPContentVerifierBuilderProvider().setProvider(
                                Constants.BOUNCY_CASTLE_PROVIDER_NAME), mPublicKey);
                        if (sig.verifyCertification(rawUserId, mPublicKey)) {
                            found = rawUserId;
                            foundSig = sig;
                            // this one can't be relevant anymore at this point
                            revocation = null;
                        }
                    }

                } catch (Exception e) {
                    // nothing bad happens, the key is just not considered the primary key id
                }
            }
        }
        if (found != null) {
            return Strings.fromUTF8ByteArray(found);
        } else {
            return null;
        }
    }

    /**
     * Returns primary user id if existing. If not, return first encountered user id.
     */
    public String getPrimaryUserIdWithFallback()  {
        String userId = getPrimaryUserId();
        if (userId == null) {
            userId = (String) mPublicKey.getUserIDs().next();
        }
        return userId;
    }

    public ArrayList<String> getUnorderedUserIds() {
        ArrayList<String> userIds = new ArrayList<String>();
        for (String userId : new IterableIterator<String>(mPublicKey.getUserIDs())) {
            userIds.add(userId);
        }
        return userIds;
    }

    public ArrayList<byte[]> getUnorderedRawUserIds() {
        ArrayList<byte[]> userIds = new ArrayList<byte[]>();
        for (byte[] userId : new IterableIterator<byte[]>(mPublicKey.getRawUserIDs())) {
            userIds.add(userId);
        }
        return userIds;
    }

    public boolean isElGamalEncrypt() {
        return getAlgorithm() == PGPPublicKey.ELGAMAL_ENCRYPT;
    }

    public boolean isDSA() {
        return getAlgorithm() == PGPPublicKey.DSA;
    }

    public boolean isEC() {
        return getAlgorithm() == PGPPublicKey.ECDH || getAlgorithm() == PGPPublicKey.ECDSA;
    }

    /**
     * Get all key usage flags.
     * If at least one key flag subpacket is present return these.
     * If no subpacket is present it returns null.
     */
    @SuppressWarnings("unchecked")
    public Integer getKeyUsage() {
        if (mCacheUsage == null) {
            for (PGPSignature sig : new IterableIterator<PGPSignature>(mPublicKey.getSignatures())) {
                if (mPublicKey.isMasterKey() && sig.getKeyID() != mPublicKey.getKeyID()) {
                    continue;
                }

                PGPSignatureSubpacketVector hashed = sig.getHashedSubPackets();
                if (hashed != null && hashed.getSubpacket(SignatureSubpacketTags.KEY_FLAGS) != null) {
                    // init if at least one key flag subpacket has been found
                    if (mCacheUsage == null) {
                        mCacheUsage = 0;
                    }
                    mCacheUsage |= hashed.getKeyFlags();
                }
            }
        }
        return mCacheUsage;
    }

    public boolean canCertify() {
        // if key flags subpacket is available, honor it!
        if (getKeyUsage() != null) {
            return (getKeyUsage() & KeyFlags.CERTIFY_OTHER) != 0;
        }

        if (mPublicKey.getAlgorithm() == PGPPublicKey.RSA_GENERAL
                || mPublicKey.getAlgorithm() == PGPPublicKey.RSA_SIGN
                || mPublicKey.getAlgorithm() == PGPPublicKey.ECDSA) {
            return true;
        }

        return false;
    }

    public boolean canSign() {
        // if key flags subpacket is available, honor it!
        if (getKeyUsage() != null) {
            return (getKeyUsage() & KeyFlags.SIGN_DATA) != 0;
        }

        if (mPublicKey.getAlgorithm() == PGPPublicKey.RSA_GENERAL
                || mPublicKey.getAlgorithm() == PGPPublicKey.RSA_SIGN
                || mPublicKey.getAlgorithm() == PGPPublicKey.ECDSA) {
            return true;
        }

        return false;
    }

    public boolean canEncrypt() {
        // if key flags subpacket is available, honor it!
        if (getKeyUsage() != null) {
            return (getKeyUsage() & (KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE)) != 0;
        }

        // RSA_GENERAL, RSA_ENCRYPT, ELGAMAL_ENCRYPT, ELGAMAL_GENERAL, ECDH
        if (mPublicKey.isEncryptionKey()) {
            return true;
        }

        return false;
    }

    public boolean canAuthenticate() {
        // if key flags subpacket is available, honor it!
        if (getKeyUsage() != null) {
            return (getKeyUsage() & KeyFlags.AUTHENTICATION) != 0;
        }

        return false;
    }

    public byte[] getFingerprint() {
        return mPublicKey.getFingerprint();
    }

    // TODO This method should have package visibility - no access outside the pgp package!
    // (It's still used in ProviderHelper at this point)
    public PGPPublicKey getPublicKey() {
        return mPublicKey;
    }

    public Iterator<WrappedSignature> getSignatures() {
        final Iterator<PGPSignature> it = mPublicKey.getSignatures();
        return new Iterator<WrappedSignature>() {
            public void remove() {
                it.remove();
            }
            public WrappedSignature next() {
                return new WrappedSignature(it.next());
            }
            public boolean hasNext() {
                return it.hasNext();
            }
        };
    }

    public Iterator<WrappedSignature> getSignaturesForRawId(byte[] rawUserId) {
        final Iterator<PGPSignature> it = mPublicKey.getSignaturesForID(rawUserId);
        if (it != null) {
            return new Iterator<WrappedSignature>() {
                public void remove() {
                    it.remove();
                }
                public WrappedSignature next() {
                    return new WrappedSignature(it.next());
                }
                public boolean hasNext() {
                    return it.hasNext();
                }
            };
        } else {
            return null;
        }
    }

}
