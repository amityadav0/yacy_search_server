// kelondroBLOBHeapReader.java
// (C) 2008 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 30.12.2008 on http://yacy.net
//
// $LastChangedDate: 2008-03-14 01:16:04 +0100 (Fr, 14 Mrz 2008) $
// $LastChangedRevision$
// $LastChangedBy$
//
// LICENSE
// 
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package net.yacy.kelondro.blob;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;

import net.yacy.kelondro.index.HandleMap;
import net.yacy.kelondro.index.RowSpaceExceededException;
import net.yacy.kelondro.io.CachedFileWriter;
import net.yacy.kelondro.io.Writer;
import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.order.ByteOrder;
import net.yacy.kelondro.order.CloneableIterator;
import net.yacy.kelondro.order.NaturalOrder;
import net.yacy.kelondro.order.RotateIterator;
import net.yacy.kelondro.util.FileUtils;
import net.yacy.kelondro.util.MemoryControl;


public class HeapReader {

    public final static long keepFreeMem = 20 * 1024 * 1024;
    
    // input values
    protected int                keylength;  // the length of the primary key
    protected File               heapFile;   // the file of the heap
    protected final ByteOrder    ordering;   // the ordering on keys
    
    // computed values
    protected Writer             file;       // a random access to the file
    protected HandleMap          index;      // key/seek relation for used records
    protected Gap                free;       // set of {seek, size} pairs denoting space and position of free records
    
    public HeapReader(
            final File heapFile,
            final int keylength,
            final ByteOrder ordering) throws IOException {
        this.ordering = ordering;
        this.heapFile = heapFile;
        this.keylength = keylength;
        this.index = null; // will be created as result of initialization process
        this.free = null; // will be initialized later depending on existing idx/gap file
        this.heapFile.getParentFile().mkdirs();
        this.file = new CachedFileWriter(this.heapFile);
        
        // read or initialize the index
        if (initIndexReadDump()) {
            // verify that everything worked just fine
            // pick some elements of the index
            Iterator<byte[]> i = this.index.keys(true, null);
            int c = 3;
            byte[] b, b1 = new byte[index.row().primaryKeyLength];
            long pos;
            boolean ok = true;
            while (i.hasNext() && c-- > 0) {
                b = i.next();
                pos = this.index.get(b);
                file.seek(pos + 4);
                file.readFully(b1, 0, b1.length);
                if (!this.ordering.equal(b, b1)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                Log.logWarning("HeapReader", "verification of idx file for " + heapFile.toString() + " failed, re-building index");
                initIndexReadFromHeap();
            } else {
                Log.logInfo("HeapReader", "using a dump of the index of " + heapFile.toString() + ".");
            }
        } else {
            // if we did not have a dump, create a new index
            initIndexReadFromHeap();
        }
        
        // merge gaps that follow directly
        mergeFreeEntries();
        
        // after the initial initialization of the heap, we close the file again
        // to make more room to file pointers which may run out if the number
        // of file descriptors is too low and the number of files is too high
        this.file.close();
        // the file will be opened again automatically when the next access to it comes.
    }
    
    public long mem() {
        return index.mem(); // don't add the memory for free here since then the asserts for memory management don't work
    }

    public void trim() {
        this.index.trim();
    }
    
    protected byte[] normalizeKey(byte[] key) {
        // check size of key: zero-filled keys are only possible of the ordering is
        // an instance of the natural ordering. Base64-orderings cannot use zeros in keys.
        assert key.length >= this.keylength || this.ordering instanceof NaturalOrder;
        return normalizeKey(key, this.keylength);
    }
    
    private static final byte zero = 0;
    
    protected static byte[] normalizeKey(byte[] key, int keylength) {
        if (key.length == keylength) return key;
        byte[] k = new byte[keylength];
        if (key.length < keylength) {
            System.arraycopy(key, 0, k, 0, key.length);
            for (int i = key.length; i < keylength; i++) k[i] = zero;
        } else {
            System.arraycopy(key, 0, k, 0, keylength);
        }
        return k;
    }
    
    private boolean initIndexReadDump() {
        // look for an index dump and read it if it exist
        // if this is successful, return true; otherwise false
        String fingerprint = HeapWriter.fingerprintFileHash(this.heapFile);
        if (fingerprint == null) {
            Log.logSevere("HeapReader", "cannot generate a fingerprint for " + this.heapFile + ": null");
            return false;
        }
        File fif = HeapWriter.fingerprintIndexFile(this.heapFile, fingerprint);
        if (!fif.exists()) fif = new File(fif.getAbsolutePath() + ".gz");
        File fgf = HeapWriter.fingerprintGapFile(this.heapFile, fingerprint);
        if (!fgf.exists()) fgf = new File(fgf.getAbsolutePath() + ".gz");
        if (!fif.exists() || !fgf.exists()) {
            HeapWriter.deleteAllFingerprints(this.heapFile);
            return false;
        }
        
        // there is an index and a gap file:
        // read the index file:
        try {
            this.index = new HandleMap(this.keylength, this.ordering, 8, fif);
        } catch (IOException e) {
            Log.logException(e);
            return false;
        } catch (RowSpaceExceededException e) {
            Log.logException(e);
            return false;
        }

        // check saturation
        int[] saturation = this.index.saturation();
        Log.logInfo("HeapReader", "saturation of " + fif.getName() + ": keylength = " + saturation[0] + ", vallength = " + saturation[1] + ", possible saving: " + ((this.keylength - saturation[0] + 8 - saturation[1]) * index.size() / 1024 / 1024) + " MB");
        
        // an index file is a one-time throw-away object, so just delete it now
        FileUtils.deletedelete(fif);
        
        // read the gap file:
        try {
            this.free = new Gap(fgf);
        } catch (IOException e) {
            Log.logException(e);
            return false;
        }
        // same with gap file
        FileUtils.deletedelete(fgf);
        
        // everything is fine now
        return !this.index.isEmpty();
    }
    
    private void initIndexReadFromHeap() throws IOException {
        // this initializes the this.index object by reading positions from the heap file
        Log.logInfo("HeapReader", "generating index for " + heapFile.toString() + ", " + (file.length() / 1024 / 1024) + " MB. Please wait.");
        
        this.free = new Gap();
        HandleMap.initDataConsumer indexready = HandleMap.asynchronusInitializer(this.name() + ".initializer", keylength, this.ordering, 8, Math.max(10, (int) (Runtime.getRuntime().freeMemory() / (10 * 1024 * 1024))));
        byte[] key = new byte[keylength];
        int reclen;
        long seek = 0;
        loop: while (true) { // don't test available() here because this does not work for files > 2GB
            
            try {
                // go to seek position
                file.seek(seek);
            
                // read length of the following record without the length of the record size bytes
                reclen = file.readInt();
                //assert reclen > 0 : " reclen == 0 at seek pos " + seek;
                if (reclen == 0) {
                    // very bad file inconsistency
                    Log.logSevere("kelondroBLOBHeap", "reclen == 0 at seek pos " + seek + " in file " + heapFile);
                    this.file.setLength(seek); // delete everything else at the remaining of the file :-(
                    break loop;
                }
                
                // read key
                file.readFully(key, 0, key.length);
                
            } catch (final IOException e) {
                // EOF reached
                break loop; // terminate loop
            }
            
            // check if this record is empty
            if (key == null || key[0] == 0) {
                // it is an empty record, store to free list
                if (reclen > 0) free.put(seek, reclen);
            } else {
                if (this.ordering.wellformed(key)) {
                    indexready.consume(key, seek);
                    key = new byte[keylength];
                } else {
                    // free the lost space
                    free.put(seek, reclen);
                    file.seek(seek + 4);
                    Arrays.fill(key, (byte) 0);
                    file.write(key); // mark the place as empty record
                    Log.logWarning("kelondroBLOBHeap", "BLOB " + heapFile.getName() + ": skiped not wellformed key " + new String(key) + " at seek pos " + seek);
                }
            }            
            // new seek position
            seek += 4L + reclen;
        }
        indexready.finish();
        
        // finish the index generation
        try {
            this.index = indexready.result();
        } catch (InterruptedException e) {
            Log.logException(e);
        } catch (ExecutionException e) {
            Log.logException(e);
        }
        Log.logInfo("HeapReader", "finished index generation for " + heapFile.toString() + ", " + index.size() + " entries, " + free.size() + " gaps.");
    }
    
    private void mergeFreeEntries() throws IOException {

        // try to merge free entries
        if (free.size() > 1) {
            int merged = 0;
            Map.Entry<Long, Integer> lastFree, nextFree;
            final Iterator<Map.Entry<Long, Integer>> i = this.free.entrySet().iterator();
            lastFree = i.next();
            while (i.hasNext()) {
                nextFree = i.next();
                //System.out.println("*** DEBUG BLOB: free-seek = " + nextFree.seek + ", size = " + nextFree.size);
                // check if they follow directly
                if (lastFree.getKey() + lastFree.getValue() + 4 == nextFree.getKey()) {
                    // merge those records
                    this.file.seek(lastFree.getKey());
                    lastFree.setValue(lastFree.getValue() + nextFree.getValue() + 4); // this updates also the free map
                    this.file.writeInt(lastFree.getValue());
                    this.file.seek(nextFree.getKey());
                    this.file.writeInt(0);
                    i.remove();
                    merged++;
                } else {
                    lastFree = nextFree;
                }
            }
            Log.logInfo("kelondroBLOBHeap", "BLOB " + heapFile.toString() + ": merged " + merged + " free records");
        }
    }
    
    public String name() {
        return this.heapFile.toString();
    }
    
    public File location() {
        return this.heapFile;
    }
    
    /**
     * the number of BLOBs in the heap
     * @return the number of BLOBs in the heap
     */
    public int size() {
        synchronized (index) {
            return (this.index == null) ? 0 : this.index.size();
        }
    }
    
    public boolean isEmpty() {
        if (this.index == null) return true;
        synchronized (index) {
            return this.index.isEmpty();
        }
    }
    
    /**
     * test if a key is in the heap file. This does not need any IO, because it uses only the ram index
     * @param key
     * @return true if the key exists, false otherwise
     */
    public boolean containsKey(byte[] key) {
        assert index != null;
        key = normalizeKey(key);
        
        synchronized (this.index) {
            // check if the file index contains the key
            return index.get(key) >= 0;
        }
    }

    public ByteOrder ordering() {
        return this.ordering;
    }
    
    /**
     * find a special key in the heap: the one with the smallest key
     * this method is useful if the entries are ordered using their keys.
     * then the key with the smallest key denotes the first entry
     * @return the smallest key in the heap
     * @throws IOException
     */
    protected synchronized byte[] firstKey() throws IOException {
        synchronized (this.index) {
            return index.smallestKey();
        }
    }
    
    /**
     * find a special blob in the heap: one that has the smallest key
     * this method is useful if the entries are ordered using their keys.
     * then the key with the smallest key denotes the first entry
     * @return the entry which key is the smallest in the heap
     * @throws IOException
     */
    protected byte[] first() throws IOException, RowSpaceExceededException {
        synchronized (this.index) {
            byte[] key = index.smallestKey();
            if (key == null) return null;
            return get(key);
        }
    }
    
    /**
     * find a special key in the heap: the one with the largest key
     * this method is useful if the entries are ordered using their keys.
     * then the key with the largest key denotes the last entry
     * @return the largest key in the heap
     * @throws IOException
     */
    protected byte[] lastKey() throws IOException {
        synchronized (this.index) {
            return index.largestKey();
        }
    }
    
    /**
     * find a special blob in the heap: one that has the largest key
     * this method is useful if the entries are ordered using their keys.
     * then the key with the largest key denotes the last entry
     * @return the entry which key is the smallest in the heap
     * @throws IOException
     */
    protected byte[] last() throws IOException, RowSpaceExceededException {
        synchronized (this.index) {
            byte[] key = index.largestKey();
            if (key == null) return null;
            return get(key);
        }
    }
    
    /**
     * read a blob from the heap
     * @param key
     * @return
     * @throws IOException
     */
    public byte[] get(byte[] key) throws IOException, RowSpaceExceededException {
        key = normalizeKey(key);
       
        synchronized (this.index) {
            // check if the index contains the key
            final long pos = index.get(key);
            if (pos < 0) return null;
            
            // access the file and read the container
            file.seek(pos);
            final int len = file.readInt() - index.row().primaryKeyLength;
            if (MemoryControl.available() < len * 2 + keepFreeMem) {
                if (!MemoryControl.request(len * 2 + keepFreeMem, true)) throw new RowSpaceExceededException(len * 2 + keepFreeMem, "HeapReader.get()"); // not enough memory available for this blob
            }
            
            // read the key
            final byte[] keyf = new byte[index.row().primaryKeyLength];
            file.readFully(keyf, 0, keyf.length);
            if (!this.ordering.equal(key, keyf)) {
                // verification of the indexed access failed. we must re-read the index
                Log.logSevere("kelondroBLOBHeap", "verification indexed access for " + heapFile.toString() + " failed, re-building index");
                // this is a severe operation, it should never happen.
                // but if the process ends in this state, it would completely fail
                // if the index is not rebuild now at once
                initIndexReadFromHeap();
            }
            
            // read the blob
            byte[] blob = new byte[len];
            file.readFully(blob, 0, blob.length);
            
            return blob;
        }
    }

    public byte[] get(Object key) {
        if (!(key instanceof byte[])) return null;
        try {
            return get((byte[]) key);
        } catch (IOException e) {
            Log.logException(e);
        } catch (RowSpaceExceededException e) {
            Log.logException(e);
        }
        return null;
    }
    
    protected boolean checkKey(byte[] key, final long pos) throws IOException {
        key = normalizeKey(key);
        file.seek(pos);
        file.readInt(); // skip the size value
        
        // read the key
        final byte[] keyf = new byte[index.row().primaryKeyLength];
        file.readFully(keyf, 0, keyf.length);
        return this.ordering.equal(key, keyf);
    }

    /**
     * retrieve the size of the BLOB. This should not be used excessively, because it depends on IO operations.
     * @param key
     * @return the size of the BLOB or -1 if the BLOB does not exist
     * @throws IOException
     */
    public long length(byte[] key) throws IOException {
        key = normalizeKey(key);
        
        synchronized (this.index) {
            // check if the index contains the key
            final long pos = index.get(key);
            if (pos < 0) return -1;
            
            // access the file and read the size of the container
            file.seek(pos);
            return file.readInt() - index.row().primaryKeyLength;
        }
    }
    
    /**
     * close the BLOB table
     */
    public void close(boolean writeIDX) {
        synchronized (this.index) {
            if (file != null)
    			try {
    				file.close();
    			} catch (IOException e) {
    			    Log.logException(e);
    			}
            file = null;
            if (writeIDX && index != null && free != null && (index.size() > 3 || free.size() > 3)) {
                // now we can create a dump of the index and the gap information
                // to speed up the next start
                try {
                    long start = System.currentTimeMillis();
                    String fingerprint = HeapWriter.fingerprintFileHash(this.heapFile);
                    if (fingerprint == null) {
                        Log.logSevere("kelondroBLOBHeap", "cannot write a dump for " + heapFile.getName()+ ": fingerprint is null");
                    } else {
                        free.dump(HeapWriter.fingerprintGapFile(this.heapFile, fingerprint));
                    }
                    free.clear();
                    free = null;
                    if (fingerprint != null) {
                        index.dump(HeapWriter.fingerprintIndexFile(this.heapFile, fingerprint));
                        Log.logInfo("kelondroBLOBHeap", "wrote a dump for the " + this.index.size() +  " index entries of " + heapFile.getName()+ " in " + (System.currentTimeMillis() - start) + " milliseconds.");
                    }
                    index.close();
                    index = null;
                } catch (IOException e) {
                    Log.logException(e);
                }
            } else {
                // this is small.. just free resources, do not write index
                if (free != null) free.clear();
                free = null;
                if (index != null) index.close();
                index = null;
            }
        }
    }
    
    public synchronized void close() {
        close(true);
    }
    
    @Override
    public void finalize() {
        this.close();
    }
    
    /**
     * ask for the length of the primary key
     * @return the length of the key
     */
    public int keylength() {
        return this.index.row().primaryKeyLength;
    }
    
    /**
     * iterator over all keys
     * @param up
     * @param rotating
     * @return
     * @throws IOException
     */
    public CloneableIterator<byte[]> keys(final boolean up, final boolean rotating) throws IOException {
        synchronized (this.index) {
            return new RotateIterator<byte[]>(this.index.keys(up, null), null, this.index.size());
        }
    }

    /**
     * iterate over all keys
     * @param up
     * @param firstKey
     * @return
     * @throws IOException
     */
    public CloneableIterator<byte[]> keys(final boolean up, final byte[] firstKey) throws IOException {
        synchronized (this.index) {
            return this.index.keys(up, firstKey);
        }
    }

    public long length() {
        synchronized (this.index) {
            return this.heapFile.length();
        }
    }
    
    /**
     * static iterator of entries in BLOBHeap files:
     * this is used to import heap dumps into a write-enabled index heap
     */
    public static class entries implements
        CloneableIterator<Map.Entry<byte[], byte[]>>,
        Iterator<Map.Entry<byte[], byte[]>>,
        Iterable<Map.Entry<byte[], byte[]>> {
        
        DataInputStream is;
        int keylen;
        private final File blobFile;
        Map.Entry<byte[], byte[]> nextEntry;
        
        public entries(final File blobFile, final int keylen) throws IOException {
            if (!(blobFile.exists())) throw new IOException("file " + blobFile + " does not exist");
            this.is = new DataInputStream(new BufferedInputStream(new FileInputStream(blobFile), 8*1024*1024));
            this.keylen = keylen;
            this.blobFile = blobFile;
            this.nextEntry = next0();
        }

        public CloneableIterator<Entry<byte[], byte[]>> clone(Object modifier) {
            // if the entries iterator is cloned, close the file!
            if (is != null) try { is.close(); } catch (final IOException e) {}
            is = null;
            try {
                return new entries(blobFile, keylen);
            } catch (IOException e) {
                Log.logException(e);
                return null;
            }
        }
        
        public boolean hasNext() {
            if (is == null) return false;
            if  (this.nextEntry != null) return true;
            close();
            return false;
        }

        private Map.Entry<byte[], byte[]> next0() {
            try {
                byte b;
                int len;
                byte[] payload;
                byte[] key;
                final int keylen1 = this.keylen - 1;
                while (true) {
                    len = is.readInt();
                    if (len == 0) continue; // rare, but possible: zero length record (takes 4 bytes)
                    b = is.readByte();      // read a single by te to check for empty record
                    if (b == 0) {
                        // this is empty
                        // read some more bytes to consume the empty record
                        if (len > 1) {
                        	if (len - 1 != is.skipBytes(len - 1)) {   // all that is remaining
	                            Log.logWarning("HeapReader", "problem skiping " +  + len + " bytes in " + this.blobFile.getName());
	                            return null;
                        	}
                        }
                        continue;
                    }
                    // we are now ahead of remaining this.keylen - 1 bytes of the key
                    key = new byte[this.keylen];
                    key[0] = b;             // the first entry that we know already
                    if (is.read(key, 1, keylen1) < keylen1) return null; // read remaining key bytes
                    // so far we have read this.keylen - 1 + 1 = this.keylen bytes.
                    // there must be a remaining number of len - this.keylen bytes left for the BLOB
                    if (len < this.keylen) return null;    // a strange case that can only happen in case of corrupted data
//                    if (is.available() < (len - this.keylen)) { // this really indicates corrupted data but doesn't work for >2GB Blobs
//                        Log.logWarning("HeapReader", "corrupted data by entry of " + len + " bytes at available of: " + is.available() + " in " + this.blobFile.getName());
//                        return null;
//                    }
                    payload = new byte[len - this.keylen]; // the remaining record entries
                    if (is.read(payload) < payload.length) return null;
                    return new entry(key, payload);
                }
            } catch (final IOException e) {
                return null;
            }
        }
        
        public Map.Entry<byte[], byte[]> next() {
            final Map.Entry<byte[], byte[]> n = this.nextEntry;
            this.nextEntry = next0();
            return n;
        }

        public void remove() {
            throw new UnsupportedOperationException("blobs cannot be altered during read-only iteration");
        }

        public Iterator<Map.Entry<byte[], byte[]>> iterator() {
            return this;
        }
        
        public void close() {
            if (is != null) try { is.close(); } catch (final IOException e) {}
            is = null;
        }
        
        @Override
        protected void finalize() {
            this.close();
        }
    }

    public static class entry implements Map.Entry<byte[], byte[]> {
        private final byte[] s;
        private byte[] b;
        
        public entry(final byte[] s, final byte[] b) {
            this.s = s;
            this.b = b;
        }
    
        public byte[] getKey() {
            return s;
        }

        public byte[] getValue() {
            return b;
        }

        public byte[] setValue(byte[] value) {
            byte[] b1 = b;
            b = value;
            return b1;
        }
    }
    
    public static void main(final String args[]) {
        File f = new File(args[0]);
        try {
            entries hr = new HeapReader.entries(f, 12);
            Map.Entry<byte[], byte[]> entry;
            while (hr.hasNext()) {
                entry = hr.next();
                System.out.println(new String(entry.getKey()) + ":" + new String(entry.getValue()));
            }
        } catch (IOException e) {
            Log.logException(e);
        }
        
    }
    
}
