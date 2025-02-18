/*
 This file is part of jpcsp.

 Jpcsp is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Jpcsp is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Jpcsp.  If not, see <http://www.gnu.org/licenses/>.
 */
package jpcsp.util;

import static java.lang.System.arraycopy;
import static jpcsp.Memory.addressMask;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import jpcsp.Emulator;
import jpcsp.Memory;
import jpcsp.MemoryMap;
import jpcsp.NIDMapper;
import jpcsp.Allegrex.Common;
import jpcsp.Allegrex.Common.Instruction;
import jpcsp.Allegrex.CpuState;
import jpcsp.Allegrex.Decoder;
import jpcsp.Allegrex.Instructions;
import jpcsp.Allegrex.compiler.RuntimeContext;
import jpcsp.Allegrex.compiler.RuntimeContextLLE;
import jpcsp.HLE.HLEModuleFunction;
import jpcsp.HLE.HLEModuleManager;
import jpcsp.HLE.Modules;
import jpcsp.HLE.TPointer;
import jpcsp.HLE.TPointer16;
import jpcsp.HLE.TPointer32;
import jpcsp.HLE.TPointer64;
import jpcsp.HLE.TPointer8;
import jpcsp.HLE.VFS.IVirtualFile;
import jpcsp.HLE.VFS.IVirtualFileSystem;
import jpcsp.HLE.VFS.fat.FatFileInfo;
import jpcsp.HLE.kernel.Managers;
import jpcsp.HLE.kernel.types.SceIoDirent;
import jpcsp.HLE.kernel.types.SceModule;
import jpcsp.HLE.modules.IoFileMgrForUser;
import jpcsp.filesystems.SeekableDataInput;
import jpcsp.filesystems.SeekableRandomFile;
import jpcsp.filesystems.umdiso.UmdIsoFile;
import jpcsp.memory.IMemoryReader;
import jpcsp.memory.IMemoryWriter;
import jpcsp.memory.IntArrayMemory;
import jpcsp.memory.MemoryReader;
import jpcsp.memory.MemoryWriter;
import jpcsp.memory.mmio.MMIO;

public class Utilities {
	public static final int KB = 1024;
	public static final int MB = 1024 * KB;
	public static final long GB = 1024L * MB;
    private static final int[] round4 = {0, 3, 2, 1};
    public  static final String lineSeparator = System.getProperty("line.separator");
    private static final char[] lineTemplate = (lineSeparator + "0x00000000 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00  >................<").toCharArray();
    private static final char[] hexDigits = "0123456789ABCDEF".toCharArray();
    private static final char[] ascii = new char[256];
	static {
		for (int i = 0; i < ascii.length; i++) {
			char c = (char) i;
	        if (c < ' ' || c > '~') {
	            c = '.';
	        }
	        ascii[i] = c;
		}
	}

    public static String formatString(String type, String oldstring) {
        int counter = 0;
        if (type.equals("byte")) {
            counter = 2;
        }
        if (type.equals("short")) {
            counter = 4;
        }
        if (type.equals("long")) {
            counter = 8;
        }
        int len = oldstring.length();
        StringBuilder sb = new StringBuilder();
        while (len++ < counter) {
            sb.append('0');
        }
        oldstring = sb.append(oldstring).toString();
        return oldstring;

    }

    public static String integerToBin(int value) {
        return Long.toBinaryString(0x0000000100000000L | ((value) & 0x00000000FFFFFFFFL)).substring(1);
    }

    public static String integerToHex(int value) {
        return Integer.toHexString(0x100 | value).substring(1).toUpperCase();
    }

    public static String integerToHexShort(int value) {
        return Integer.toHexString(0x10000 | value).substring(1).toUpperCase();
    }

    public static long readUWord(SeekableDataInput f) throws IOException {
        long l = (f.readUnsignedByte() | (f.readUnsignedByte() << 8) | (f.readUnsignedByte() << 16) | (f.readUnsignedByte() << 24));
        return (l & 0xFFFFFFFFL);
    }

    public static int readUByte(SeekableDataInput f) throws IOException {
        return f.readUnsignedByte();
    }

    public static int readUHalf(SeekableDataInput f) throws IOException {
        return f.readUnsignedByte() | (f.readUnsignedByte() << 8);
    }

    public static int readWord(SeekableDataInput f) throws IOException {
        //readByte() isn't more correct? (already exists one readUWord() method to unsign values)
        return (f.readUnsignedByte() | (f.readUnsignedByte() << 8) | (f.readUnsignedByte() << 16) | (f.readUnsignedByte() << 24));
    }

    public static void skipUnknown(ByteBuffer buf, int length) throws IOException {
        buf.position(buf.position() + length);
    }

    public static String readStringZ(ByteBuffer buf) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte b;
        for (; buf.position() < buf.limit();) {
            b = (byte) readUByte(buf);
            if (b == 0) {
                break;
            }
            sb.append((char) b);
        }
        return sb.toString();
    }

    public static String readStringNZ(ByteBuffer buf, int n) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte b;
        for (; n > 0; n--) {
            b = (byte) readUByte(buf);
            if (b != 0) {
                sb.append((char) b);
            }
        }
        return sb.toString();
    }

    public static int getMaxLength(int address) {
    	return Math.max(0, MemoryMap.END_RAM - address + 1);
    }

    /**
     * Read a string from memory. The string ends when the maximal length is
     * reached or a '\0' byte is found. The memory bytes are interpreted as
     * UTF-8 bytes to form the string.
     *
     * @param mem the memory
     * @param address the address of the first byte of the string
     * @param n the maximal string length
     * @return the string converted to UTF-8
     */
    public static String readStringNZ(Memory mem, int address, int n) {
    	if (mem == RuntimeContext.memory) {
	        address &= Memory.addressMask;
	        if (address + n > MemoryMap.END_RAM) {
	            n = getMaxLength(address);
	        }
    	}

        // Allocate a byte array to store the bytes of the string.
        // At first, allocate maximum 10000 bytes in case we don't know
        // the maximal string length. The array will be extended if required.
        byte[] bytes = new byte[Math.min(n, 10000)];

        int length = 0;
        IMemoryReader memoryReader = MemoryReader.getMemoryReader(mem, address, n, 1);
        for (; n > 0; n--) {
            int b = memoryReader.readNext();
            if (b == 0) {
                break;
            }

            if (length >= bytes.length) {
                // Extend the bytes array
            	bytes = extendArray(bytes, 10000);
            }

            bytes[length++] = (byte) b;
        }

        // Convert the bytes to UTF-8
        return new String(bytes, 0, length, Constants.charset);
    }

    public static String readStringZ(Memory mem, int address) {
        address &= Memory.addressMask;
        return readStringNZ(mem, address, getMaxLength(address));
    }

    public static String readInternalStringZ(Memory mem, int address) {
        address &= Memory.addressMask;
    	return readInternalStringNZ(mem, address, getMaxLength(address));
    }

    public static String readInternalStringNZ(Memory mem, int address, int n) {
		byte[] bytes = new byte[256];
		int length = 0;
		for (int i = 0; i < n; i++) {
			int b = mem.internalRead8(address + i);
			if (b == 0) {
				break;
			}

			if (length >= bytes.length) {
                // Extend the bytes array
            	bytes = extendArray(bytes, 256);
			}
			bytes[length++] = (byte) b;
		}

		// Convert the bytes to UTF-8
		return new String(bytes, 0, length, Constants.charset);
    }

    public static String readStringZ(int address) {
        return readStringZ(Memory.getInstance(), address);
    }

    public static String readStringNZ(byte[] buffer, int offset, int n) {
    	StringBuilder s = new StringBuilder();
    	for (int i = 0; i < n; i++) {
    		byte b = buffer[offset + i];
    		if (b == (byte) 0) {
    			break;
    		}
    		s.append((char) b);
    	}

    	return s.toString();
    }

    public static String readStringZ(byte[] buffer, int offset) {
    	StringBuilder s = new StringBuilder();
    	while (offset < buffer.length) {
    		byte b = buffer[offset++];
    		if (b == (byte) 0) {
    			break;
    		}
    		s.append((char) b);
    	}

    	return s.toString();
    }

    public static String readStringNZ(int address, int n) {
        return readStringNZ(Memory.getInstance(), address, n);
    }

    public static void writeStringNZ(Memory mem, int address, int n, String s) {
        int offset = 0;
        IMemoryWriter memoryWriter = MemoryWriter.getMemoryWriter(mem, address, n, 1);
        if (s != null) {
            byte[] bytes = s.getBytes(Constants.charset);
            while (offset < bytes.length && offset < n) {
                memoryWriter.writeNext(bytes[offset]);
                offset++;
            }
        }
        while (offset < n) {
            memoryWriter.writeNext(0);
            offset++;
        }
        memoryWriter.flush();
    }

    public static void writeStringNZ(byte[] buffer, int offset, int n, String s) {
        if (s != null) {
            byte[] bytes = s.getBytes(Constants.charset);
            int length = Math.min(n, bytes.length);
            System.arraycopy(bytes, 0, buffer, offset, length);
            if (length < n) {
            	Arrays.fill(buffer, offset + length, offset + n, (byte) 0);
            }
        } else {
        	Arrays.fill(buffer, offset, offset + n, (byte) 0);
        }
    }

    public static void writeStringZ(Memory mem, int address, String s) {
        // add 1 to the length to write the final '\0'
        writeStringNZ(mem, address, s.length() + 1, s);
    }

    public static void writeStringZ(ByteBuffer buf, String s) {
        buf.put(s.getBytes());
        buf.put((byte) 0);
    }

    public static int getUnsignedByte(ByteBuffer bb) throws IOException {
        return bb.get() & 0xFF;
    }

    public static void putUnsignedByte(ByteBuffer bb, int value) {
        bb.put((byte) (value & 0xFF));
    }

    public static int readUByte(ByteBuffer buf) throws IOException {
        return getUnsignedByte(buf);
    }

    public static int readUHalf(ByteBuffer buf) throws IOException {
        return getUnsignedByte(buf) | (getUnsignedByte(buf) << 8);
    }

    public static int readUWord(ByteBuffer buf) throws IOException {
    	// No difference between signed and unsigned word (32-bit value)
    	return readWord(buf);
    }

    public static int readWord(ByteBuffer buf) throws IOException {
        return getUnsignedByte(buf) | (getUnsignedByte(buf) << 8) | (getUnsignedByte(buf) << 16) | (getUnsignedByte(buf) << 24);
    }

    public static int read8(IVirtualFile vFile) throws IOException {
    	byte[] buffer = new byte[1];
    	int result = vFile.ioRead(buffer, 0, buffer.length);
    	if (result < buffer.length) {
    		return 0;
    	}

    	return buffer[0] & 0xFF;
    }

    public static int read32(IVirtualFile vFile) throws IOException {
    	return read8(vFile) | (read8(vFile) << 8) | (read8(vFile) << 16) | (read8(vFile) << 24);
    }

    public static void writeWord(ByteBuffer buf, int value) {
        putUnsignedByte(buf, value >> 0);
        putUnsignedByte(buf, value >> 8);
        putUnsignedByte(buf, value >> 16);
        putUnsignedByte(buf, value >> 24);
    }

    public static void writeHalf(ByteBuffer buf, int value) {
        putUnsignedByte(buf, value >> 0);
        putUnsignedByte(buf, value >> 8);
    }

    public static void writeByte(ByteBuffer buf, int value) {
        putUnsignedByte(buf, value);
    }

    public static int parseAddress(String s) throws NumberFormatException {
        int address = 0;
        if (s == null) {
            return address;
        }

        s = s.trim();

        if (s.startsWith("0x")) {
            s = s.substring(2);
        }

        if (s.length() == 8 && s.charAt(0) >= '8') {
            address = (int) Long.parseLong(s, 16);
        } else {
            address = Integer.parseInt(s, 16);
        }

        return address;
    }

    public static int parseInteger(String s) throws NumberFormatException {
        int value = 0;
        if (s == null) {
            return value;
        }

        s = s.trim();

        boolean neg = false;
        if (s.startsWith("-")) {
        	s = s.substring(1);
        	neg = true;
        }

        int base = 10;
        if (s.startsWith("0x")) {
            s = s.substring(2);
            base = 16;
        }

        if (s.length() == 8 && s.charAt(0) >= '8') {
            value = (int) Long.parseLong(s, base);
        } else {
            value = Integer.parseInt(s, base);
        }

        if (neg) {
        	value = -value;
        }

        return value;
    }

    public static int getRegister(String s) {
    	for (int i = 0; i < Common.gprNames.length; i++) {
    		if (Common.gprNames[i].equalsIgnoreCase(s)) {
    			return i;
    		}
    	}

    	return -1;
    }

    public static int parseAddressExpression(String s) {
    	if (s == null) {
    		return 0;
    	}

    	s = s.trim();

    	// Build a pattern matching all Gpr register names
    	String regPattern = "";
    	for (String gprName : Common.gprNames) {
    		regPattern += "\\" + gprName + "|";
    	}

    	Memory mem = Emulator.getMemory();
    	CpuState cpu = Emulator.getProcessor().cpu;
    	Pattern p;
    	Matcher m;

    	// Parse e.g.: "$a0"
    	p = Pattern.compile(regPattern);
    	m = p.matcher(s);
    	if (m.matches()) {
    		int reg = getRegister(s);
    		if (reg >= 0) {
    			return cpu.getRegister(reg);
    		}
    	}

    	// Parse e.g.: "16($a0)", "0xc($a1)"
    	p = Pattern.compile("((0x)?\\p{XDigit}+)\\((" + regPattern + ")\\)");
    	m = p.matcher(s);
    	if (m.matches()) {
    		int offset = parseInteger(m.group(1));
    		int reg = getRegister(m.group(3));

    		if (reg >= 0) {
    			return mem.read32(cpu.getRegister(reg) + offset);
    		}
    	}

    	// Parse e.g.: "$a0 + 16", "$a1 - 0xc"
    	p = Pattern.compile("(" + regPattern + ")\\s*([+\\-])\\s*((0x)?\\p{XDigit}+)");
    	m = p.matcher(s);
    	if (m.matches()) {
    		int reg = getRegister(m.group(1));
    		int offset = parseInteger(m.group(3));
    		if (m.group(2).equals("-")) {
    			offset = -offset;
    		}

    		if (reg >= 0) {
    			return cpu.getRegister(reg) + offset;
    		}
    	}

    	return Utilities.parseAddress(s);
    }

    /**
     * Parse the string as a number and returns its value. If the string starts
     * with "0x", the number is parsed in base 16, otherwise base 10.
     *
     * @param s the string to be parsed
     * @return the numeric value represented by the string.
     */
    public static long parseLong(String s) {
        long value = 0;

        if (s == null) {
            return value;
        }

        if (s.startsWith("0x")) {
            value = Long.parseLong(s.substring(2), 16);
        } else {
            value = Long.parseLong(s);
        }
        return value;
    }

    /**
     * Parse the string as a number and returns its value. The number is always
     * parsed in base 16. The string can start as an option with "0x".
     *
     * @param s the string to be parsed in base 16
     * @param ignoreTrailingChars true if trailing (i.e. non-hex characters) have to be ignored
     *                            false if non-hex characters have to raise an exception NumberFormatException
     * @return the numeric value represented by the string.
     */
    public static long parseHexLong(String s, boolean ignoreTrailingChars) {
        long value = 0;

        if (s == null) {
            return value;
        }

        if (s.startsWith("0x")) {
            s = s.substring(2);
        }

        if (ignoreTrailingChars && s.length() > 0) {
        	for (int i = 0; i < s.length(); i++) {
        		char c = s.charAt(i);
        		// Is it an hexadecimal character?
            	if ("0123456789abcdefABCDEF".indexOf(c) < 0) {
            		// Delete the trailing non-hex characters
            		s = s.substring(0, i);
            		break;
            	}
        	}
        }

        value = Long.parseLong(s, 16);
        return value;
    }

    public static int makePow2(int n) {
        --n;
        n = (n >> 1) | n;
        n = (n >> 2) | n;
        n = (n >> 4) | n;
        n = (n >> 8) | n;
        n = (n >> 16) | n;
        return ++n;
    }

    /**
     * Check if a value is a power of 2, i.e. a value that be can computed as (1 << x).
     * 
     * @param n      value to be checked
     * @return       true if the value is a power of 2,
     *               false otherwise.
     */
    public static boolean isPower2(int n) {
    	return (n & (n - 1)) == 0;
    }

    public static void readFully(SeekableDataInput input, TPointer address, int length) throws IOException {
        final int blockSize = 16 * UmdIsoFile.sectorLength;  // 32Kb
        byte[] buffer = null;
        int offset = 0;
        while (length > 0) {
            int size = Math.min(length, blockSize);
            if (buffer == null || size != buffer.length) {
                buffer = new byte[size];
            }
            input.readFully(buffer);
            address.getMemory().copyToMemory(address.getAddress() + offset, ByteBuffer.wrap(buffer), size);
            offset += size;
            length -= size;
        }
    }

    public static void write(SeekableRandomFile output, TPointer address, int length) throws IOException {
        Buffer buffer = address.getMemory().getBuffer(address.getAddress(), length);
        if (buffer instanceof ByteBuffer) {
            output.getChannel().write((ByteBuffer) buffer);
        } else if (length > 0) {
            byte[] bytes = new byte[length];
            IMemoryReader memoryReader = MemoryReader.getMemoryReader(address, length, 1);
            for (int i = 0; i < length; i++) {
                bytes[i] = (byte) memoryReader.readNext();
            }
            output.write(bytes);
        }
    }

    public static void bytePositionBuffer(Buffer buffer, int bytePosition) {
        buffer.position(bytePosition / bufferElementSize(buffer));
    }

    public static int bufferElementSize(Buffer buffer) {
        if (buffer instanceof IntBuffer) {
            return 4;
        }

        return 1;
    }

    public static String stripNL(String s) {
        if (s != null && s.endsWith("\n")) {
            s = s.substring(0, s.length() - 1);
        }

        return s;
    }

    public static void putBuffer(ByteBuffer destination, Buffer source, ByteOrder sourceByteOrder) {
        // Set the destination to the desired ByteOrder
        ByteOrder order = destination.order();
        destination.order(sourceByteOrder);

        if (source instanceof IntBuffer) {
            destination.asIntBuffer().put((IntBuffer) source);
        } else if (source instanceof ShortBuffer) {
            destination.asShortBuffer().put((ShortBuffer) source);
        } else if (source instanceof ByteBuffer) {
            destination.put((ByteBuffer) source);
        } else if (source instanceof FloatBuffer) {
            destination.asFloatBuffer().put((FloatBuffer) source);
        } else {
            Modules.log.error("Utilities.putBuffer: Unsupported Buffer type " + source.getClass().getName());
            Emulator.PauseEmuWithStatus(Emulator.EMU_STATUS_UNIMPLEMENTED);
        }

        // Reset the original ByteOrder of the destination
        destination.order(order);
    }

    public static void putBuffer(ByteBuffer destination, Buffer source, ByteOrder sourceByteOrder, int lengthInBytes) {
        // Set the destination to the desired ByteOrder
        ByteOrder order = destination.order();
        destination.order(sourceByteOrder);

        int srcLimit = source.limit();
        if (source instanceof IntBuffer) {
            int copyLength = lengthInBytes & ~3;
            destination.asIntBuffer().put((IntBuffer) source.limit(source.position() + (copyLength >> 2)));
            int restLength = lengthInBytes - copyLength;
            if (restLength > 0) {
                // 1 to 3 bytes left to copy
                source.limit(srcLimit);
                int value = ((IntBuffer) source).get();
                int position = destination.position() + copyLength;
                do {
                    destination.put(position, (byte) value);
                    value >>= 8;
                    restLength--;
                    position++;
                } while (restLength > 0);
            }
        } else if (source instanceof ByteBuffer) {
            destination.put((ByteBuffer) source.limit(source.position() + lengthInBytes));
        } else if (source instanceof ShortBuffer) {
            int copyLength = lengthInBytes & ~1;
            destination.asShortBuffer().put((ShortBuffer) source.limit(source.position() + (copyLength >> 1)));
            int restLength = lengthInBytes - copyLength;
            if (restLength > 0) {
                // 1 byte left to copy
                source.limit(srcLimit);
                short value = ((ShortBuffer) source).get();
                destination.put(destination.position() + copyLength, (byte) value);
            }
        } else if (source instanceof FloatBuffer) {
            int copyLength = lengthInBytes & ~3;
            destination.asFloatBuffer().put((FloatBuffer) source.limit(source.position() + (copyLength >> 2)));
            int restLength = lengthInBytes - copyLength;
            if (restLength > 0) {
                // 1 to 3 bytes left to copy
                source.limit(srcLimit);
                int value = Float.floatToRawIntBits(((FloatBuffer) source).get());
                int position = destination.position() + copyLength;
                do {
                    destination.put(position, (byte) value);
                    value >>= 8;
                    restLength--;
                    position++;
                } while (restLength > 0);
            }
        } else {
            Emulator.log.error("Utilities.putBuffer: Unsupported Buffer type " + source.getClass().getName());
            Emulator.PauseEmuWithStatus(Emulator.EMU_STATUS_UNIMPLEMENTED);
        }

        // Reset the original ByteOrder of the destination
        destination.order(order);
        // Reset the original limit of the source
        source.limit(srcLimit);
    }

    /**
     * Reads inputstream i into a String with the UTF-8 charset until the
     * inputstream is finished (don't use with infinite streams).
     *
     * @param inputStream to read into a string
     * @param close if true, close the inputstream
     * @return a string
     * @throws java.io.IOException if thrown on reading the stream
     * @throws java.lang.NullPointerException if the given inputstream is null
     */
    public static String toString(InputStream inputStream, boolean close) throws IOException {
        if (inputStream == null) {
            throw new NullPointerException("null inputstream");
        }
        String string;
        StringBuilder outputBuilder = new StringBuilder();

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
            while (null != (string = reader.readLine())) {
                outputBuilder.append(string).append('\n');
            }
        } finally {
            if (close) {
                close(reader, inputStream);
            }
        }
        return outputBuilder.toString();
    }

    /**
     * Close closeables. Use this in a finally clause.
     */
    public static void close(Closeable... closeables) {
        for (Closeable c : closeables) {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ex) {
                    Logger.getLogger(Utilities.class.getName()).log(Level.WARNING, "Couldn't close Closeable", ex);
                }
            }
        }
    }

    public static int getSizeKb(long sizeByte) {
        return (int) ((sizeByte + 1023) / 1024);
    }

    private static void addAsciiDump(StringBuilder dump, IMemoryReader charReader, int bytesPerLine) {
        dump.append("  >");
        for (int i = 0; i < bytesPerLine; i++) {
        	dump.append(ascii[charReader.readNext()]);
        }
        dump.append("<");
    }

    private static String getMemoryDump(int address, int length, int step, int bytesPerLine, IMemoryReader memoryReader, IMemoryReader charReader) {
        if (length <= 0 || bytesPerLine <= 0 || step <= 0) {
            return "";
        }

    	StringBuilder dump = new StringBuilder();

        if (length < bytesPerLine) {
            bytesPerLine = length;
        }

        String format = String.format(" %%0%dX", step * 2);
        for (int i = 0; i < length; i += step) {
            if ((i % bytesPerLine) < step) {
                if (i > 0) {
                    // Add an ASCII representation at the end of the line
                    addAsciiDump(dump, charReader, bytesPerLine);
                }
                dump.append(lineSeparator);
                dump.append(String.format("0x%08X", address + i));
            }

            int value = memoryReader.readNext();
            if (length - i >= step) {
                dump.append(String.format(format, value));
            } else {
                switch (length - i) {
                    case 3:
                        dump.append(String.format(" %06X", value & 0x00FFFFFF));
                        break;
                    case 2:
                        dump.append(String.format(" %04X", value & 0x0000FFFF));
                        break;
                    case 1:
                        dump.append(String.format(" %02X", value & 0x000000FF));
                        break;
                }
            }
        }

        int lengthLastLine = length % bytesPerLine;
        if (lengthLastLine > 0) {
            for (int i = lengthLastLine; i < bytesPerLine; i++) {
                dump.append("  ");
                if ((i % step) == 0) {
                    dump.append(" ");
                }
            }
            addAsciiDump(dump, charReader, lengthLastLine);
        } else {
            addAsciiDump(dump, charReader, bytesPerLine);
        }

        return dump.toString();
    }

    // Optimize the most common case
    private static String getMemoryDump(int[] memoryInt, int address, int length) {
        if (length <= 0) {
            return "";
        }

        final int numberLines = length >> 4;
    	final char[] chars = new char[numberLines * lineTemplate.length];
    	final int lineOffset = lineSeparator.length() + 2;

    	for (int i = 0, j = 0, a = (address & Memory.addressMask) >> 2; i < numberLines; i++, j += lineTemplate.length, address += 16) {
    		System.arraycopy(lineTemplate, 0, chars, j, lineTemplate.length);

    		// Address field
    		int k = j + lineOffset;
    		chars[k++] = hexDigits[(address >>> 28)      ];
    		chars[k++] = hexDigits[(address >>  24) & 0xF];
    		chars[k++] = hexDigits[(address >>  20) & 0xF];
    		chars[k++] = hexDigits[(address >>  16) & 0xF];
    		chars[k++] = hexDigits[(address >>  12) & 0xF];
    		chars[k++] = hexDigits[(address >>   8) & 0xF];
    		chars[k++] = hexDigits[(address >>   4) & 0xF];
    		chars[k++] = hexDigits[(address       ) & 0xF];
    		k++;

    		// First 32-bit value
    		int value = memoryInt[a++];
    		if (value != 0) {
	    		chars[k++] = hexDigits[(value >>   4) & 0xF];
	    		chars[k++] = hexDigits[(value       ) & 0xF];
	    		k++;
	    		chars[k++] = hexDigits[(value >>  12) & 0xF];
	    		chars[k++] = hexDigits[(value >>   8) & 0xF];
	    		k++;
	    		chars[k++] = hexDigits[(value >>  20) & 0xF];
	    		chars[k++] = hexDigits[(value >>  16) & 0xF];
	    		k++;
	    		chars[k++] = hexDigits[(value >>> 28)      ];
	    		chars[k++] = hexDigits[(value >>  24) & 0xF];
	    		k++;

	    		chars[k + 38] = ascii[(value       ) & 0xFF];
	    		chars[k + 39] = ascii[(value >>   8) & 0xFF];
	    		chars[k + 40] = ascii[(value >>  16) & 0xFF];
	    		chars[k + 41] = ascii[(value >>> 24)       ];
    		} else {
    			k += 12;
    		}

    		// Second 32-bit value
    		value = memoryInt[a++];
    		if (value != 0) {
	    		chars[k++] = hexDigits[(value >>   4) & 0xF];
	    		chars[k++] = hexDigits[(value       ) & 0xF];
	    		k++;
	    		chars[k++] = hexDigits[(value >>  12) & 0xF];
	    		chars[k++] = hexDigits[(value >>   8) & 0xF];
	    		k++;
	    		chars[k++] = hexDigits[(value >>  20) & 0xF];
	    		chars[k++] = hexDigits[(value >>  16) & 0xF];
	    		k++;
	    		chars[k++] = hexDigits[(value >>> 28)      ];
	    		chars[k++] = hexDigits[(value >>  24) & 0xF];
	    		k++;

	    		chars[k + 30] = ascii[(value       ) & 0xFF];
	    		chars[k + 31] = ascii[(value >>   8) & 0xFF];
	    		chars[k + 32] = ascii[(value >>  16) & 0xFF];
	    		chars[k + 33] = ascii[(value >>> 24)       ];
    		} else {
    			k += 12;
    		}

    		// Third 32-bit value
    		value = memoryInt[a++];
    		if (value != 0) {
	    		chars[k++] = hexDigits[(value >>   4) & 0xF];
	    		chars[k++] = hexDigits[(value       ) & 0xF];
	    		k++;
	    		chars[k++] = hexDigits[(value >>  12) & 0xF];
	    		chars[k++] = hexDigits[(value >>   8) & 0xF];
	    		k++;
	    		chars[k++] = hexDigits[(value >>  20) & 0xF];
	    		chars[k++] = hexDigits[(value >>  16) & 0xF];
	    		k++;
	    		chars[k++] = hexDigits[(value >>> 28)      ];
	    		chars[k++] = hexDigits[(value >>  24) & 0xF];
	    		k++;

	    		chars[k + 22] = ascii[(value       ) & 0xFF];
	    		chars[k + 23] = ascii[(value >>   8) & 0xFF];
	    		chars[k + 24] = ascii[(value >>  16) & 0xFF];
	    		chars[k + 25] = ascii[(value >>> 24)       ];
    		} else {
    			k += 12;
    		}

    		// Fourth 32-bit value
    		value = memoryInt[a++];
    		if (value != 0) {
	    		chars[k++] = hexDigits[(value >>   4) & 0xF];
	    		chars[k++] = hexDigits[(value       ) & 0xF];
	    		k++;
	    		chars[k++] = hexDigits[(value >>  12) & 0xF];
	    		chars[k++] = hexDigits[(value >>   8) & 0xF];
	    		k++;
	    		chars[k++] = hexDigits[(value >>  20) & 0xF];
	    		chars[k++] = hexDigits[(value >>  16) & 0xF];
	    		k++;
	    		chars[k++] = hexDigits[(value >>> 28)      ];
	    		chars[k++] = hexDigits[(value >>  24) & 0xF];
	    		k += 15;

	    		chars[k++] = ascii[(value       ) & 0xFF];
	    		chars[k++] = ascii[(value >>   8) & 0xFF];
	    		chars[k++] = ascii[(value >>  16) & 0xFF];
	    		chars[k  ] = ascii[(value >>> 24)       ];
    		}
    	}

    	return new String(chars);
    }

    public static String getMemoryDump(int address, int length) {
    	if (RuntimeContext.hasMemoryInt() && (length & 0xF) == 0 && (address & 0x3) == 0 && Memory.isAddressGood(address)) {
    	    // The most common case has been optimized
    		return getMemoryDump(RuntimeContext.getMemoryInt(), address, length);
    	}

    	// Convenience function using default step and bytesPerLine
        return getMemoryDump(address, length, 1, 16);
    }

    public static String getMemoryDump(Memory mem, int address, int length) {
    	// Convenience function using default step and bytesPerLine
    	return getMemoryDump(mem, address, length, 1, 16);
    }

    public static String getMemoryDump(Memory mem, int address, int length, int step, int bytesPerLine) {
    	IMemoryReader memoryReader = MemoryReader.getMemoryReader(mem, address, length, step);
    	IMemoryReader charReader = MemoryReader.getMemoryReader(mem, address, length, 1);

    	return getMemoryDump(address, length, step, bytesPerLine, memoryReader, charReader);
    }

    public static String getMemoryDump(TPointer address, int length) {
    	return getMemoryDump(address.getMemory(), address.getAddress(), length);
    }

    public static String getMemoryDump(int address, int length, int step, int bytesPerLine) {
    	Memory mem = Memory.getInstance();
    	if (!Memory.isAddressGood(address)) {
    		if (!RuntimeContextLLE.hasMMIO() || !MMIO.isAddressGood(address)) {
        		return String.format("Invalid memory address 0x%08X", address);
    		}
			mem = RuntimeContextLLE.getMMIO();
    	}

        IMemoryReader memoryReader = MemoryReader.getMemoryReader(mem, address, length, step);
        IMemoryReader charReader = MemoryReader.getMemoryReader(mem, address, length, 1);

        return getMemoryDump(address, length, step, bytesPerLine, memoryReader, charReader);
    }

    public static String getMemoryDump(byte[] bytes) {
		return getMemoryDump(bytes, 0, bytes == null ? 0 : bytes.length);
    }

    public static String getMemoryDump(byte[] bytes, int offset, int length) {
        // Convenience function using default step and bytesPerLine
        return getMemoryDump(bytes, offset, length, 1, 16);
    }

    public static String getMemoryDump(byte[] bytes, int offset, int length, int step, int bytesPerLine) {
        if (bytes == null || length <= 0 || bytesPerLine <= 0 || step <= 0) {
            return "";
        }

        IMemoryReader memoryReader = MemoryReader.getMemoryReader(0, bytes, offset, length, step);
        IMemoryReader charReader = MemoryReader.getMemoryReader(0, bytes, offset, length, step);

        return getMemoryDump(0, length, step, bytesPerLine, memoryReader, charReader);
    }

    public static int alignUp(int value, int alignment) {
        return alignDown(value + alignment, alignment);
    }

    public static int alignDown(int value, int alignment) {
        return value & ~alignment;
    }

    public static long alignDown(long value, long alignment) {
    	return value & ~alignment;
    }

    public static int endianSwap32(int x) {
        return Integer.reverseBytes(x);
    }

	public static void endianSwap32(Memory mem, int address, int length) {
		for (int i = 0; i < length; i += 4) {
			mem.write32(address + i, endianSwap32(mem.read32(address + i)));
		}
	}

	public static void endianSwap32(byte[] buffer, int offset, int length) {
		for (int i = 0; i < length; i += 4) {
			writeUnaligned32(buffer, offset + i, endianSwap32(readUnaligned32(buffer, offset + i)));
		}
	}

	public static int endianSwap16(int x) {
        return ((x >> 8) & 0x00FF) | ((x << 8) & 0xFF00);
    }

    public static long endianSwap64(long x) {
        return Long.reverseBytes(x);
    }

    public static int readUnaligned32(Memory mem, int address) {
        switch (address & 3) {
            case 0:
                return mem.read32(address);
            case 2:
                return mem.read16(address) | (mem.read16(address + 2) << 16);
            default:
                return (mem.read8(address + 3) << 24)
                        | (mem.read8(address + 2) << 16)
                        | (mem.read8(address + 1) << 8)
                        | (mem.read8(address));
        }
    }

    public static int internalReadUnaligned32(Memory mem, int address) {
        switch (address & 3) {
            case 0:
                return mem.internalRead32(address);
            case 2:
                return mem.internalRead16(address) | (mem.internalRead16(address + 2) << 16);
            default:
                return (mem.internalRead8(address + 3) << 24)
                     | (mem.internalRead8(address + 2) << 16)
                     | (mem.internalRead8(address + 1) << 8)
                     | (mem.internalRead8(address));
        }
    }

    public static int readUnaligned16(Memory mem, int address) {
    	if ((address & 1) == 0) {
    		return mem.read16(address);
    	}
    	return (mem.read8(address + 1) << 8) | mem.read8(address);
    }

    public static long readUnaligned64(Memory mem, int address) {
        if ((address & 3) == 0) {
    		return mem.read64(address);
        }

        return (readUnaligned32(mem, address) & 0xFFFFFFFFL) | (((long) readUnaligned32(mem, address + 4)) << 32);
    }

    public static int u8(byte value) {
    	return value & 0xFF;
    }

    public static int u16(short value) {
    	return value & 0xFFFF;
    }

    public static int read8(byte[] buffer, int offset) {
        return u8(buffer[offset]);
    }

    public static int readUnaligned32(byte[] buffer, int offset) {
        return (read8(buffer, offset + 3) << 24)
                | (read8(buffer, offset + 2) << 16)
                | (read8(buffer, offset + 1) << 8)
                | (read8(buffer, offset));
    }

    public static long readUnaligned64(byte[] buffer, int offset) {
        return (((long) read8(buffer, offset + 7)) << 56)
        		| (((long) read8(buffer, offset + 6)) << 48)
        		| (((long) read8(buffer, offset + 5)) << 40)
        		| (((long) read8(buffer, offset + 4)) << 32)
        		| (((long) read8(buffer, offset + 3)) << 24)
                | (((long) read8(buffer, offset + 2)) << 16)
                | (((long) read8(buffer, offset + 1)) << 8)
                | (((long) read8(buffer, offset)));
    }

    public static int readUnaligned16(byte[] buffer, int offset) {
        return (read8(buffer, offset + 1) << 8) | read8(buffer, offset);
    }

    public static void writeUnaligned32(Memory mem, int address, int data) {
        switch (address & 3) {
            case 0:
                mem.write32(address, data);
                break;
            case 2:
                mem.write16(address, (short) data);
                mem.write16(address + 2, (short) (data >> 16));
                break;
            default:
                mem.write8(address, (byte) data);
                mem.write8(address + 1, (byte) (data >> 8));
                mem.write8(address + 2, (byte) (data >> 16));
                mem.write8(address + 3, (byte) (data >> 24));
        }
    }

    public static void writeUnaligned16(Memory mem, int address, int data) {
    	if ((address & 1) == 0) {
            mem.write16(address, (short) data);
    	} else {
    		mem.write8(address, (byte) data);
            mem.write8(address + 1, (byte) (data >> 8));
    	}
    }

    public static void writeUnaligned64(Memory mem, int address, long data) {
    	if ((address & 3) == 0) {
    		mem.write64(address, data);
    	} else {
    		writeUnaligned32(mem, address, (int) data);
    		writeUnaligned32(mem, address + 4, (int) (data >> 32));
    	}
    }

    public static void write8(byte[] buffer, int offset, int data) {
    	buffer[offset] = (byte) data;
    }

    public static void writeUnaligned32(byte[] buffer, int offset, int data) {
    	buffer[offset + 0] = (byte) data;
    	buffer[offset + 1] = (byte) (data >> 8);
    	buffer[offset + 2] = (byte) (data >> 16);
    	buffer[offset + 3] = (byte) (data >> 24);
    }

    public static void writeUnaligned16(byte[] buffer, int offset, int data) {
    	buffer[offset + 0] = (byte) data;
    	buffer[offset + 1] = (byte) (data >> 8);
    }

    public static void writeUnaligned64(byte[] buffer, int offset, long data) {
    	buffer[offset + 0] = (byte) data;
    	buffer[offset + 1] = (byte) (data >> 8);
    	buffer[offset + 2] = (byte) (data >> 16);
    	buffer[offset + 3] = (byte) (data >> 24);
    	buffer[offset + 4] = (byte) (data >> 32);
    	buffer[offset + 5] = (byte) (data >> 40);
    	buffer[offset + 6] = (byte) (data >> 48);
    	buffer[offset + 7] = (byte) (data >> 56);
    }

    public static int min(int a, int b) {
        return Math.min(a, b);
    }

    public static float min(float a, float b) {
        return Math.min(a, b);
    }

    public static int max(int a, int b) {
        return Math.max(a, b);
    }

    public static float max(float a, float b) {
        return Math.max(a, b);
    }

    /**
     * Minimum value rounded down.
     *
     * @param a first float value
     * @param b second float value
     * @return the largest int value that is less than or equal to both
     * parameters
     */
    public static int minInt(float a, float b) {
        return floor(min(a, b));
    }

    /**
     * Minimum value rounded down.
     *
     * @param a first int value
     * @param b second float value
     * @return the largest int value that is less than or equal to both
     * parameters
     */
    public static int minInt(int a, float b) {
        return min(a, floor(b));
    }

    /**
     * Maximum value rounded up.
     *
     * @param a first float value
     * @param b second float value
     * @return the smallest int value that is greater than or equal to both
     * parameters
     */
    public static int maxInt(float a, float b) {
        return ceil(max(a, b));
    }

    /**
     * Maximum value rounded up.
     *
     * @param a first float value
     * @param b second float value
     * @return the smallest int value that is greater than or equal to both
     * parameters
     */
    public static int maxInt(int a, float b) {
        return max(a, ceil(b));
    }

    public static int min(int a, int b, int c) {
        return Math.min(a, Math.min(b, c));
    }

    public static int max(int a, int b, int c) {
        return Math.max(a, Math.max(b, c));
    }

    public static void sleep(int micros) {
        sleep(micros / 1000, micros % 1000);
    }

    public static void sleep(int millis, int micros) {
    	if (millis < 0) {
    		return;
    	}

    	try {
            if (micros <= 0) {
                Thread.sleep(millis);
            } else {
                Thread.sleep(millis, micros * 1000);
            }
        } catch (InterruptedException e) {
            // Ignore exception
        }
    }

    public static void matrixMult(final float[] result, float[] m1, float[] m2) {
        // If the result has to be stored into one of the input matrix,
        // duplicate the input matrix.
        if (result == m1) {
            m1 = m1.clone();
        }
        if (result == m2) {
            m2 = m2.clone();
        }

        int i = 0;
        for (int j = 0; j < 16; j += 4) {
            for (int x = 0; x < 4; x++) {
                result[i] = m1[x] * m2[j]
                        + m1[x + 4] * m2[j + 1]
                        + m1[x + 8] * m2[j + 2]
                        + m1[x + 12] * m2[j + 3];
                i++;
            }
        }
    }

    public static void vectorMult(final float[] result, final float[] m, final float[] v) {
        for (int i = 0; i < result.length; i++) {
            float s = v[0] * m[i];
            int k = i + 4;
            for (int j = 1; j < v.length; j++) {
                s += v[j] * m[k];
                k += 4;
            }
            result[i] = s;
        }
    }

    public static void vectorMult33(final float[] result, final float[] m, final float[] v) {
        result[0] = v[0] * m[0] + v[1] * m[4] + v[2] * m[8];
        result[1] = v[0] * m[1] + v[1] * m[5] + v[2] * m[9];
        result[2] = v[0] * m[2] + v[1] * m[6] + v[2] * m[10];
    }

    public static void vectorMult34(final float[] result, final float[] m, final float[] v) {
        result[0] = v[0] * m[0] + v[1] * m[4] + v[2] * m[8] + v[3] * m[12];
        result[1] = v[0] * m[1] + v[1] * m[5] + v[2] * m[9] + v[3] * m[13];
        result[2] = v[0] * m[2] + v[1] * m[6] + v[2] * m[10] + v[3] * m[14];
    }

    public static void vectorMult44(final float[] result, final float[] m, final float[] v) {
        result[0] = v[0] * m[0] + v[1] * m[4] + v[2] * m[8] + v[3] * m[12];
        result[1] = v[0] * m[1] + v[1] * m[5] + v[2] * m[9] + v[3] * m[13];
        result[2] = v[0] * m[2] + v[1] * m[6] + v[2] * m[10] + v[3] * m[14];
        result[3] = v[0] * m[3] + v[1] * m[7] + v[2] * m[11] + v[3] * m[15];
    }

    // This is equivalent to Math.round but faster: Math.round is using StrictMath.
    public static int round(float n) {
        return (int) (n + .5f);
    }

    public static int floor(float n) {
        return (int) Math.floor(n);
    }

    public static int ceil(float n) {
        return (int) Math.ceil(n);
    }

    public static int getPower2(int n) {
        return Integer.numberOfTrailingZeros(makePow2(n));
    }

    public static void copy(boolean[] to, boolean[] from) {
        arraycopy(from, 0, to, 0, to.length);
    }

    public static void copy(boolean[][] to, boolean[][] from) {
    	for (int i = 0; i < to.length; i++) {
    		copy(to[i], from[i]);
    	}
    }

    public static void copy(int[] to, int[] from) {
        arraycopy(from, 0, to, 0, to.length);
    }

    public static void copy(int[][] to, int[][] from) {
    	for (int i = 0; i < to.length; i++) {
    		copy(to[i], from[i]);
    	}
    }

    public static void copy(int[][][] to, int[][][] from) {
    	for (int i = 0; i < to.length; i++) {
    		copy(to[i], from[i]);
    	}
    }

    public static void copy(int[][][][] to, int[][][][] from) {
    	for (int i = 0; i < to.length; i++) {
    		copy(to[i], from[i]);
    	}
    }

    public static void copy(float[] to, float[] from) {
        arraycopy(from, 0, to, 0, to.length);
    }

    public static void copy(float[][] to, float[][] from) {
    	for (int i = 0; i < to.length; i++) {
    		copy(to[i], from[i]);
    	}
    }

    public static void copy(float[][][] to, float[][][] from) {
    	for (int i = 0; i < to.length; i++) {
    		copy(to[i], from[i]);
    	}
    }

    public static void copy(float[][][][] to, float[][][][] from) {
    	for (int i = 0; i < to.length; i++) {
    		copy(to[i], from[i]);
    	}
    }

    public static float dot3(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    public static float dot3(float[] a, float x, float y, float z) {
        return a[0] * x + a[1] * y + a[2] * z;
    }

    public static float length3(float[] a) {
        return (float) Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
    }

    public static float invertedLength3(float[] a) {
        float length = length3(a);
        if (length == 0.f) {
            return 0.f;
        }
        return 1.f / length;
    }

    public static void normalize3(float[] result, float[] a) {
        float invertedLength = invertedLength3(a);
        result[0] = a[0] * invertedLength;
        result[1] = a[1] * invertedLength;
        result[2] = a[2] * invertedLength;
    }

    public static float pow(float a, float b) {
        return (float) Math.pow(a, b);
    }

    public static float clamp(float n, float minValue, float maxValue) {
        return max(minValue, min(n, maxValue));
    }

    /**
     * Invert a 3x3 matrix.
     *
     * Based on
     * http://en.wikipedia.org/wiki/Invert_matrix#Inversion_of_3.C3.973_matrices
     *
     * @param result the inverted matrix (stored as a 4x4 matrix, but only 3x3
     * is returned)
     * @param m the matrix to be inverted (stored as a 4x4 matrix, but only 3x3
     * is used)
     * @return true if the matrix could be inverted false if the matrix could
     * not be inverted
     */
    public static boolean invertMatrix3x3(float[] result, float[] m) {
        float A = m[5] * m[10] - m[6] * m[9];
        float B = m[6] * m[8] - m[4] * m[10];
        float C = m[4] * m[9] - m[5] * m[8];
        float det = m[0] * A + m[1] * B + m[2] * C;

        if (det == 0.f) {
            // Matrix could not be inverted
            return false;
        }

        float invertedDet = 1.f / det;
        result[0] = A * invertedDet;
        result[1] = (m[2] * m[9] - m[1] * m[10]) * invertedDet;
        result[2] = (m[1] * m[6] - m[2] * m[5]) * invertedDet;
        result[4] = B * invertedDet;
        result[5] = (m[0] * m[10] - m[2] * m[8]) * invertedDet;
        result[6] = (m[2] * m[4] - m[0] * m[6]) * invertedDet;
        result[8] = C * invertedDet;
        result[9] = (m[8] * m[1] - m[0] * m[9]) * invertedDet;
        result[10] = (m[0] * m[5] - m[1] * m[4]) * invertedDet;

        return true;
    }

    public static void transposeMatrix3x3(float[] result, float[] m) {
        for (int i = 0, j = 0; i < 3; i++, j += 4) {
            result[i] = m[j];
            result[i + 4] = m[j + 1];
            result[i + 8] = m[j + 2];
        }
    }

    public static boolean sameColor(float[] c1, float[] c2, float[] c3) {
        for (int i = 0; i < 4; i++) {
            if (c1[i] != c2[i] || c1[i] != c3[i]) {
                return false;
            }
        }

        return true;
    }

    public static boolean sameColor(float[] c1, float[] c2, float[] c3, float[] c4) {
        for (int i = 0; i < 4; i++) {
            if (c1[i] != c2[i] || c1[i] != c3[i] || c1[i] != c4[i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * Transform a pixel coordinate (floating-point value "u" or "v") into a
     * texel coordinate (integer value to access the texture).
     *
     * The texel coordinate is calculated by truncating the floating point
     * value, not by rounding it. Otherwise transition problems occur at the
     * borders. E.g. if a texture has a width of 64, valid texel coordinates
     * range from 0 to 63. 64 is already outside of the texture and should not
     * be generated when approaching the border to the texture.
     *
     * @param coordinate the pixel coordinate
     * @return the texel coordinate
     */
    public static final int pixelToTexel(float coordinate) {
        return (int) coordinate;
    }

    /**
     * Wrap the value to the range [0..1[ (1 is excluded).
     *
     * E.g. value == 4.0 -> return 0.0 value == 4.1 -> return 0.1 value == 4.9
     * -> return 0.9 value == -4.0 -> return 0.0 value == -4.1 -> return 0.9
     * (and not 0.1) value == -4.9 -> return 0.1 (and not 0.9)
     *
     * @param value the value to be wrapped
     * @return the wrapped value in the range [0..1[ (1 is excluded)
     */
    public static float wrap(float value) {
        if (value >= 0.f) {
            // value == 4.0 -> return 0.0
            // value == 4.1 -> return 0.1
            // value == 4.9 -> return 0.9
            return value - (int) value;
        }

        // value == -4.0 -> return 0.0
        // value == -4.1 -> return 0.9
        // value == -4.9 -> return 0.1
        // value == -1e-8 -> return 0.0
        float wrappedValue = value - (float) Math.floor(value);
        if (wrappedValue >= 1.f) {
            wrappedValue -= 1.f;
        }
        return wrappedValue;
    }

    public static int wrap(float value, int valueMask) {
        return pixelToTexel(value) & valueMask;
    }

    public static void readBytes(int address, int length, byte[] bytes, int offset) {
        IMemoryReader memoryReader = MemoryReader.getMemoryReader(address, length, 1);
        for (int i = 0; i < length; i++) {
            bytes[offset + i] = (byte) memoryReader.readNext();
        }
    }

    public static void writeBytes(int address, int length, byte[] bytes, int offset) {
        IMemoryWriter memoryWriter = MemoryWriter.getMemoryWriter(address, length, 1);
        for (int i = 0; i < length; i++) {
            memoryWriter.writeNext(bytes[i + offset] & 0xFF);
        }
        memoryWriter.flush();
    }

    public static void writeBytes(TPointer address, int length, byte[] bytes, int offset) {
        IMemoryWriter memoryWriter = MemoryWriter.getMemoryWriter(address, length, 1);
        for (int i = 0; i < length; i++) {
            memoryWriter.writeNext(bytes[i + offset] & 0xFF);
        }
        memoryWriter.flush();
    }

    public static void readInt32(int address, int length, int[] a, int offset) {
    	final int length4 = length >> 2;
		// Optimize the most common case
		if (RuntimeContext.hasMemoryInt()) {
			System.arraycopy(RuntimeContext.getMemoryInt(), (address & addressMask) >> 2, a, offset, length4);
		} else {
			IMemoryReader memoryReader = MemoryReader.getMemoryReader(address, length, 4);
			for (int i = 0; i < length4; i++) {
				a[offset + i] = memoryReader.readNext();
			}
		}
    }

    public static void writeInt32(TPointer address, int length, int[] a, int offset) {
    	final int length4 = length >> 2;
		// Optimize the most common case
    	if (RuntimeContext.hasMemoryInt(address)) {
    		System.arraycopy(a, offset, RuntimeContext.getMemoryInt(), (address.getAddress() & addressMask) >> 2, length4);
    	} else {
	    	IMemoryWriter memoryWriter = MemoryWriter.getMemoryWriter(address, length, 4);
	    	for (int i = 0; i < length4; i++) {
	    		memoryWriter.writeNext(a[offset + i]);
	    	}
	    	memoryWriter.flush();
    	}
    }

    public static int[] readInt32(int address, int length) {
		int[] a = new int[length >> 2];
		readInt32(address, length, a, 0);

		return a;
    }

    public static int round4(int n) {
        return n + round4[n & 3];
    }

    public static int round2(int n) {
        return n + (n & 1);
    }

    public static int[] extendArray(int[] array, int extend) {
        if (array == null) {
            return new int[extend];
        }

        int[] newArray = new int[array.length + extend];
        System.arraycopy(array, 0, newArray, 0, array.length);

        return newArray;
    }

    public static byte[] extendArray(byte[] array, int extend) {
        if (array == null) {
            return new byte[extend];
        }

        byte[] newArray = new byte[array.length + extend];
        System.arraycopy(array, 0, newArray, 0, array.length);

        return newArray;
    }

    public static byte[] extendArray(byte[] array, byte[] extend) {
    	if (extend == null) {
    		return array;
    	}
    	return extendArray(array, extend, 0, extend.length);
    }

    public static byte[] extendArray(byte[] array, byte[] extend, int offset, int length) {
    	if (length <= 0) {
    		return array;
    	}

    	if (array == null) {
    		array = new byte[length];
    		System.arraycopy(extend, offset, array, 0, length);
            return array;
        }

        byte[] newArray = new byte[array.length + length];
        System.arraycopy(array, 0, newArray, 0, array.length);
        System.arraycopy(extend, offset, newArray, array.length, length);

        return newArray;
    }

    public static byte[] copyToArrayAndExtend(byte[] destination, int destinationOffset, byte[] source, int sourceOffset, int length) {
    	if (source == null || length <= 0) {
    		return destination;
    	}

    	if (destination == null) {
    		destination = new byte[destinationOffset + length];
    		System.arraycopy(source, sourceOffset, destination, destinationOffset, length);
    		return destination;
    	}

    	if (destinationOffset + length > destination.length) {
    		destination = extendArray(destination, destinationOffset + length - destination.length);
    	}

    	System.arraycopy(source, sourceOffset, destination, destinationOffset, length);

    	return destination;
    }

    public static TPointer[] extendArray(TPointer[] array, int extend) {
        if (array == null) {
            return new TPointer[extend];
        }

        TPointer[] newArray = new TPointer[array.length + extend];
        System.arraycopy(array, 0, newArray, 0, array.length);

        return newArray;
    }

    public static String[] add(String[] array, String s) {
    	if (s == null) {
    		return array;
    	}
    	if (array == null) {
    		return new String[] { s };
    	}

    	String[] newArray = new String[array.length + 1];
    	System.arraycopy(array, 0, newArray, 0, array.length);
    	newArray[array.length] = s;

    	return newArray;
    }

    public static String[] add(String[] array, String[] strings) {
    	if (strings == null) {
    		return array;
    	}
    	if (array == null) {
    		return strings.clone();
    	}

    	String[] newArray = new String[array.length + strings.length];
    	System.arraycopy(array, 0, newArray, 0, array.length);
    	System.arraycopy(strings, 0, newArray, array.length, strings.length);

    	return newArray;
    }

    public static int[] add(int[] array, int n) {
    	if (array == null) {
    		return new int[] { n };
    	}

    	int[] newArray = new int[array.length + 1];
    	System.arraycopy(array, 0, newArray, 0, array.length);
    	newArray[array.length] = n;

    	return newArray;
    }

    public static byte[] add(byte[] array, byte n) {
    	if (array == null) {
    		return new byte[] { n };
    	}

    	byte[] newArray = new byte[array.length + 1];
    	System.arraycopy(array, 0, newArray, 0, array.length);
    	newArray[array.length] = n;

    	return newArray;
    }

    public static byte[] add(byte[] array, byte[] bytes) {
    	if (bytes == null) {
    		return array;
    	}

    	if (array == null) {
    		return bytes.clone();
    	}

    	byte[] newArray = new byte[array.length + bytes.length];
    	System.arraycopy(array, 0, newArray, 0, array.length);
    	System.arraycopy(bytes, 0, newArray, array.length, bytes.length);

    	return newArray;
    }

    public static File[] add(File[] array, File f) {
    	if (f == null) {
    		return array;
    	}
    	if (array == null) {
    		return new File[] { f };
    	}

    	File[] newArray = new File[array.length + 1];
    	System.arraycopy(array, 0, newArray, 0, array.length);
    	newArray[array.length] = f;

    	return newArray;
    }

    public static File[] add(File[] array, File[] files) {
    	if (files == null) {
    		return array;
    	}
    	if (array == null) {
    		return files.clone();
    	}

    	File[] newArray = new File[array.length + files.length];
    	System.arraycopy(array, 0, newArray, 0, array.length);
    	System.arraycopy(files, 0, newArray, array.length, files.length);

    	return newArray;
    }

    public static byte[] readCompleteFile(IVirtualFile vFile) {
        if (vFile == null) {
            return null;
        }

        byte[] buffer;
        try {
            buffer = new byte[(int) (vFile.length() - vFile.getPosition())];
        } catch (OutOfMemoryError e) {
            Emulator.log.error("Error while reading a complete vFile", e);
            return null;
        }

        int length = 0;
        while (length < buffer.length) {
            int readLength = vFile.ioRead(buffer, length, buffer.length - length);
            if (readLength < 0) {
                break;
            }
            length += readLength;
        }

        if (length < buffer.length) {
            byte[] resizedBuffer;
            try {
                resizedBuffer = new byte[length];
            } catch (OutOfMemoryError e) {
                Emulator.log.error("Error while reading a complete vFile", e);
                return null;
            }
            System.arraycopy(buffer, 0, resizedBuffer, 0, length);
            buffer = resizedBuffer;
        }

        return buffer;
    }

    public static byte[] readCompleteFile(String fileName) {
    	StringBuilder localFileName = new StringBuilder();
    	IVirtualFileSystem vfs = Modules.IoFileMgrForUserModule.getVirtualFileSystem(fileName, localFileName);
    	if (vfs == null) {
    		return null;
    	}

    	IVirtualFile vFile = vfs.ioOpen(localFileName.toString(), IoFileMgrForUser.PSP_O_RDONLY, 0);
    	if (vFile == null) {
    		return null;
    	}

    	int length = (int) vFile.length();
    	if (length <= 0) {
    		return null;
    	}

    	// Read the complete file
    	byte[] buffer = new byte[length];
    	int readLength = vFile.ioRead(buffer, 0, length);
    	vFile.ioClose();
    	if (readLength != length) {
    		return null;
    	}

    	return buffer;
    }

    public static boolean writeCompleteFile(String fileName, byte[] buffer, boolean createDirectories) {
    	StringBuilder localFileName = new StringBuilder();
    	IVirtualFileSystem vfs = Modules.IoFileMgrForUserModule.getVirtualFileSystem(fileName, localFileName);
    	if (vfs == null) {
    		return false;
    	}

    	if (createDirectories) {
    		int index = fileName.indexOf(":/");
    		if (index >= 0) {
    			index += 2;
    			int startDirName = index;
    			while (true) {
    				int dirIndex = fileName.indexOf('/', index);
    				if (dirIndex < 0) {
    					break;
    				}
    				String dirName = fileName.substring(startDirName, dirIndex);
    				vfs.ioMkdir(dirName, 0777);
    				index = dirIndex + 1;
    			}
    		}
    	}

    	IVirtualFile vFile = vfs.ioOpen(localFileName.toString(), IoFileMgrForUser.PSP_O_WRONLY, 0777);
    	if (vFile == null) {
    		return false;
    	}

    	// Write the complete file
    	int writeLength = vFile.ioWrite(buffer, 0, buffer.length);
    	vFile.ioClose();
    	if (writeLength != buffer.length) {
    		return false;
    	}

    	return true;
    }

    private static boolean isSystemLibraryExisting(String libraryName, String path) {
    	String[] extensions = new String[] { ".dll", ".so" };

    	path = path.trim();
    	if (!path.endsWith("/")) {
    		path += "/";
    	}

    	for (String extension : extensions) {
        	File libraryFile = new File(String.format("%s%s%s", path, libraryName, extension));
        	if (libraryFile.canExecute()) {
        		return true;
        	}
    	}

    	return false;
    }

    public static boolean isSystemLibraryExisting(String libraryName) {
    	String libraryPaths = System.getProperty("java.library.path");
    	if (libraryPaths == null) {
    		libraryPaths = "";
    	}

    	String[] paths = libraryPaths.split(File.pathSeparator);
    	for (String path : paths) {
    		if (isSystemLibraryExisting(libraryName, path)) {
    			return true;
    		}
    	}

    	return false;
    }

    public static int signExtend(int value, int bits) {
    	int shift = Integer.SIZE - bits;
    	return (value << shift) >> shift;
    }

    public static int clip(int value, int min, int max) {
    	if (value < min) {
    		return min;
    	}
    	if (value > max) {
    		return max;
    	}

    	return value;
    }

    public static float clipf(float value, float min, float max) {
    	if (value < min) {
    		return min;
    	}
    	if (value > max) {
    		return max;
    	}

    	return value;
    }

    public static void fill(int a[][], int value) {
    	for (int i = 0; i < a.length; i++) {
    		Arrays.fill(a[i], value);
    	}
    }

    public static void fill(float a[], float value) {
		Arrays.fill(a, value);
    }

    public static void fill(float a[][], float value) {
    	for (int i = 0; i < a.length; i++) {
    		Arrays.fill(a[i], value);
    	}
    }

    public static void fill(float a[][][], float value) {
    	for (int i = 0; i < a.length; i++) {
    		fill(a[i], value);
    	}
    }

    public static void fill(float a[][][][], float value) {
    	for (int i = 0; i < a.length; i++) {
    		fill(a[i], value);
    	}
    }

    public static long getReturnValue64(CpuState cpu) {
    	long low = cpu._v0;
    	long high = cpu._v1;
    	return (low & 0xFFFFFFFFL) | (high << 32);
    }

    public static int convertABGRtoARGB(int abgr) {
    	return (abgr & 0xFF00FF00) | ((abgr & 0x00FF0000) >> 16) | ((abgr & 0x000000FF) << 16);
    }

    public static void disableSslCertificateChecks() {
		try {
			TrustManager[] trustAllCerts = new TrustManager[] {
					new X509TrustManager() {
						@Override
						public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
						@Override
						public void checkClientTrusted(X509Certificate[] certs, String authType) {  }
						@Override
						public void checkServerTrusted(X509Certificate[] certs, String authType) {  }
					}
			};
			SSLContext sc = SSLContext.getInstance("SSL");
			sc.init(null, trustAllCerts, new SecureRandom());
			HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
			HostnameVerifier allHostsValid = new HostnameVerifier() {
				@Override
				public boolean verify(String hostname, SSLSession session) {
					return true;
				}
			};
			HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
		} catch (NoSuchAlgorithmException e) {
			Emulator.log.error(e);
		} catch (KeyManagementException e) {
			Emulator.log.error(e);
		}
    }

    public static int getDefaultPortForProtocol(String protocol) {
		if ("http".equals(protocol)) {
			return 80;
		}
		if ("https".equals(protocol)) {
			return 443;
		}

		return -1;
	}

	public static InetAddress[] merge(InetAddress[] a1, InetAddress[] a2) {
		if (a1 == null) {
			return a2;
		}
		if (a2 == null) {
			return a1;
		}

		InetAddress[] a = new InetAddress[a1.length + a2.length];
		System.arraycopy(a1, 0, a, 0, a1.length);
		System.arraycopy(a2, 0, a, a1.length, a2.length);

		return a;
	}

	public static InetAddress[] add(InetAddress[] array, InetAddress inetAddress) {
    	if (inetAddress == null) {
    		return array;
    	}
    	if (array == null) {
    		return new InetAddress[] { inetAddress };
    	}

    	InetAddress[] newArray = new InetAddress[array.length + 1];
    	System.arraycopy(array, 0, newArray, 0, array.length);
    	newArray[array.length] = inetAddress;

    	return newArray;
	}

	public static boolean equals(byte[] array1, int offset1, byte[] array2, int offset2, int length) {
		for (int i = 0; i < length; i++) {
			if (array1[offset1 + i] != array2[offset2 + i]) {
				return false;
			}
		}

		return true;
	}

	public static boolean equals(String a, String b) {
		if (a == null) {
			return b == null;
		}

		return a.equals(b);
	}

    public static void patch(Memory mem, SceModule module, int offset, int oldValue, int newValue) {
    	patch(mem, module, offset, oldValue, newValue, 0xFFFFFFFF);
    }

    public static void patch(Memory mem, SceModule module, int offset, int oldValue, int newValue, int mask) {
    	int checkValue = mem.read32(module.baseAddress + offset);
    	if ((checkValue & mask) != (oldValue & mask)) {
    		Emulator.log.error(String.format("Patching of module '%s' failed at offset 0x%X, 0x%08X found instead of 0x%08X", module.modname, offset, checkValue, oldValue));
    	} else {
    		mem.write32(module.baseAddress + offset, newValue);
    	}
    }

    public static void patchRemoveStringChar(Memory mem, SceModule module, int offset, int oldChar) {
    	int address = module.baseAddress + offset;
    	int checkChar = mem.read8(address);
    	if (checkChar != oldChar) {
    		Emulator.log.error(String.format("Patching of module '%s' failed at offset 0x%X, 0x%02X found instead of 0x%02X: %s", module.modname, offset, checkChar, oldChar, Utilities.getMemoryDump(address - 0x100, 0x200)));
    	} else {
    		String s = Utilities.readStringZ(address);
    		s = s.substring(1);
    		Utilities.writeStringZ(mem, address, s);
    	}
    }

    public static HLEModuleFunction getHLEFunctionByAddress(int address) {
		HLEModuleFunction func = HLEModuleManager.getInstance().getFunctionFromAddress(address);
		if (func == null) {
			func = Modules.LoadCoreForKernelModule.getHLEFunctionByAddress(address);
		}

		return func;
    }

    public static String getFunctionNameByAddress(int address) {
    	if ((address & Memory.addressMask) == 0) {
    		return null;
    	}

		Memory mem = Emulator.getMemory(address);
    	String functionName = null;

		HLEModuleFunction func = HLEModuleManager.getInstance().getFunctionFromAddress(address);
		if (func != null) {
			functionName = func.getFunctionName();
		}

		if (functionName == null) {
			functionName = Modules.LoadCoreForKernelModule.getFunctionNameByAddress(mem, address);
		}

		if (functionName == null) {
			int nextOpcode = mem.internalRead32(address + 4);
			Instruction nextInsn = Decoder.instruction(nextOpcode);
			if (nextInsn == Instructions.SYSCALL) {
				int syscallCode = (nextOpcode >> 6) & 0xFFFFF;
				functionName = getFunctionNameBySyscall(mem, syscallCode);
			}
		}

		if (functionName == null) {
			NIDMapper nidMapper = NIDMapper.getInstance();
			int nid = nidMapper.getNidByAddress(address);
			if (nid != 0) {
				int syscall = nidMapper.getSyscallByNid(nid);
				if (syscall >= 0) {
					functionName = nidMapper.getNameBySyscall(syscall);
				}

				if (functionName == null) {
					String moduleName = nidMapper.getModuleNameByAddress(address);
					if (moduleName != null && moduleName.length() > 0) {
						functionName = String.format("%s_%08X", moduleName, nid);
					}
				}
			}
		}

		if (functionName == null) {
			SceModule module = Managers.modules.getModuleByAddress(address);
			if (module != null && module.modname != null && module.modname.length() > 0) {
	    		if (address == module.module_start_func) {
	    			functionName = String.format("%s.module_start", module.modname);
	    		} else if (address == module.module_stop_func) {
	    			functionName = String.format("%s.module_stop", module.modname);
	    		} else if (address == module.module_bootstart_func) {
	    			functionName = String.format("%s.module_bootstart", module.modname);
	    		} else if (address == module.module_reboot_before_func) {
	    			functionName = String.format("%s.module_reboot_before", module.modname);
	    		} else if (address == module.module_reboot_phase_func) {
	    			functionName = String.format("%s.module_reboot_phase", module.modname);
	    		} else if (address == module.module_start_func) {
	    			functionName = String.format("%s.module_start", module.modname);
	    		} else {
	    			functionName = String.format("%s.sub_%08X", module.modname, address - module.text_addr);
	    		}
			}
		}

		return functionName;
    }

    public static String getFunctionNameBySyscall(Memory mem, int syscallCode) {
    	HLEModuleFunction func = HLEModuleManager.getInstance().getFunctionFromSyscallCode(syscallCode);
    	if (func != null) {
    		return func.getFunctionName();
    	}

    	return Modules.LoadCoreForKernelModule.getFunctionNameBySyscall(mem, syscallCode);
    }

    public static void addHex(StringBuilder s, int value) {
    	if (value == 0) {
    		s.append('0');
    		return;
    	}

    	int shift = 28 - (Integer.numberOfLeadingZeros(value) & 0x3C);
    	for (; shift >= 0; shift -= 4) {
    		int digit = (value >> shift) & 0xF;
    		s.append(hexDigits[digit]);
    	}
    }

    public static void addAddressHex(StringBuilder s, int address) {
		s.append(new char[] {
			hexDigits[(address >>> 28)      ],
			hexDigits[(address >>  24) & 0xF],
			hexDigits[(address >>  20) & 0xF],
			hexDigits[(address >>  16) & 0xF],
			hexDigits[(address >>  12) & 0xF],
			hexDigits[(address >>   8) & 0xF],
			hexDigits[(address >>   4) & 0xF],
			hexDigits[(address       ) & 0xF]
			});
    }

    public static boolean hasFlag(int value, int flag) {
    	return (value & flag) != 0;
    }

    public static boolean notHasFlag(int value, int flag) {
    	return !hasFlag(value, flag);
    }

    public static int setFlag(int value, int flag) {
    	return value | flag;
    }

    public static int setFlag(int value1, int value2, int flag) {
    	return setFlag(clearFlag(value1, flag), value2 & flag);
    }

    public static int clearFlag(int value, int flag) {
    	return value & ~flag;
    }

    public static boolean isFallingFlag(int oldValue, int newValue, int flag) {
		return (oldValue & flag) > (newValue & flag);
    }

    public static boolean isRaisingFlag(int oldValue, int newValue, int flag) {
		return (oldValue & flag) < (newValue & flag);
    }

    public static int getFlagFromBit(int bit) {
    	return 1 << bit;
    }

    public static boolean hasBit(int value, int bit) {
    	return hasFlag(value, getFlagFromBit(bit));
    }

    public static boolean notHasBit(int value, int bit) {
    	return !hasBit(value, bit);
    }

    public static int setBit(int value, int bit) {
    	return setFlag(value, getFlagFromBit(bit));
    }

    public static int setBit(int value1, int value2, int bit) {
    	return setFlag(value1, value2, getFlagFromBit(bit));
    }

    public static int clearBit(int value, int bit) {
    	return clearFlag(value, getFlagFromBit(bit));
    }

    public static boolean isFallingBit(int oldValue, int newValue, int bit) {
    	return isFallingFlag(oldValue, newValue, getFlagFromBit(bit));
	}

    public static boolean isRaisingBit(int oldValue, int newValue, int bit) {
    	return isRaisingFlag(oldValue, newValue, getFlagFromBit(bit));
	}

    public static ByteBuffer readAsByteBuffer(RandomAccessFile raf) throws IOException {
        byte[] bytes = new byte[(int) raf.length()];
        int offset = 0;
        // Read large files by chunks.
        while (offset < bytes.length) {
            int len = raf.read(bytes, offset, Math.min(10 * 1024, bytes.length - offset));
            if (len < 0) {
                break;
            }
            if (len > 0) {
                offset += len;
            }
        }

        return ByteBuffer.wrap(bytes, 0, offset);
    }

    private static IntArrayMemory allocateIntArrayMemory(int memorySize) {
    	int[] intArray = new int[alignUp(memorySize, 3) >> 2];
    	return new IntArrayMemory(intArray);
    }

    public static TPointer allocatePointer(int memorySize) {
    	return allocateIntArrayMemory(memorySize).getPointer();
    }

    public static TPointer8 allocatePointer8(int memorySize) {
    	return allocateIntArrayMemory(memorySize).getPointer8();
    }

    public static TPointer16 allocatePointer16(int memorySize) {
    	return allocateIntArrayMemory(memorySize).getPointer16();
    }

    public static TPointer32 allocatePointer32(int memorySize) {
    	return allocateIntArrayMemory(memorySize).getPointer32();
    }

    public static TPointer64 allocatePointer64(int memorySize) {
    	return allocateIntArrayMemory(memorySize).getPointer64();
    }

    public static int compareUnsigned32(int a, int b) {
    	// Equivalent to Integer.compareUnsigned(a, b) from JDK 1.8.
    	// Using Integer.compare() here for compatibility with JDK 1.7.
    	return Integer.compare(a + Integer.MIN_VALUE, b + Integer.MIN_VALUE);
    }

    public static void dumpToFile(String fileName, TPointer address, int length) {
		byte[] bytes = new byte[length];
		address.getArray8(bytes);
		try {
			OutputStream os = new FileOutputStream(fileName);
			os.write(bytes);
			os.close();
		} catch (IOException e) {
			Emulator.log.error(e);
		}
    }

	public static FatFileInfo[] add(FatFileInfo[] array, FatFileInfo value) {
    	if (value == null) {
    		return array;
    	}
    	if (array == null) {
    		return new FatFileInfo[] { value };
    	}

    	FatFileInfo[] newArray = new FatFileInfo[array.length + 1];
    	System.arraycopy(array, 0, newArray, 0, array.length);
    	newArray[array.length] = value;

    	return newArray;
	}

	public static SceIoDirent[] add(SceIoDirent[] array, SceIoDirent value) {
    	if (value == null) {
    		return array;
    	}
    	if (array == null) {
    		return new SceIoDirent[] { value };
    	}

    	SceIoDirent[] newArray = new SceIoDirent[array.length + 1];
    	System.arraycopy(array, 0, newArray, 0, array.length);
    	newArray[array.length] = value;

    	return newArray;
	}

	public static int memcmp(byte[] buf1, int offset1, byte[] buf2, int offset2, int size) {
		for (int i = 0; i < size; i++) {
			if (buf1[offset1 + i] != buf2[offset2 + i]) {
				return (buf1[offset1 + i] - buf2[offset2 + i]) & 0xFF;
			}
		}

		return 0;
	}

	public static byte[] intArrayToByteArray(int[] array) {
		if (array == null) {
			return null;
		}

		byte[] bytes = new byte[array.length];
    	for (int i = 0; i < array.length; i++) {
    		bytes[i] = (byte) array[i];
    	}

    	return bytes;
	}

	public static byte[] getArray(byte[] array, int length) {
		return getArray(array, 0, length);
	}

	public static byte[] getArray(byte[] array, int offset, int length) {
		if (array == null) {
			return null;
		}

		byte[] newArray = new byte[length];
		if (length > 0) {
			System.arraycopy(array, offset, newArray, 0, length);
		}

		return newArray;
	}

	public static void memset(byte[] array, byte value, int length) {
		memset(array, 0, value, length);
	}

	public static void memset(byte[] array, int offset, byte value, int length) {
		Arrays.fill(array, offset, offset + length, value);
	}

	public static int sizeof(byte[] array) {
		if (array == null) {
			return 0;
		}

		return array.length;
	}

	public static int getByte0(int value) {
		return value & 0xFF;
	}

	public static int getByte1(int value) {
		return getByte0(value >> 8);
	}

	public static int setByte0(int value, int value8) {
		return (value & 0xFFFFFF00) | getByte0(value8);
	}

	public static int setByte1(int value, int value8) {
		return (value & 0xFFFF00FF) | (getByte0(value8) << 8);
	}
}
