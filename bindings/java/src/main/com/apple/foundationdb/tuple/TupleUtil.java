/*
 * TupleUtil.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2013-2018 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.tuple;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.apple.foundationdb.FDB;

class TupleUtil {
	private static final byte nil = 0x00;
	private static final BigInteger[] size_limits;
	private static final Charset UTF8;
	private static final IterableComparator iterableComparator;

	private static final byte BYTES_CODE            = 0x01;
	private static final byte STRING_CODE           = 0x02;
	private static final byte NESTED_CODE           = 0x05;
	private static final byte INT_ZERO_CODE         = 0x14;
	private static final byte POS_INT_END           = 0x1d;
	private static final byte NEG_INT_START         = 0x0b;
	private static final byte FLOAT_CODE            = 0x20;
	private static final byte DOUBLE_CODE           = 0x21;
	private static final byte FALSE_CODE            = 0x26;
	private static final byte TRUE_CODE             = 0x27;
	private static final byte UUID_CODE             = 0x30;
	private static final byte VERSIONSTAMP_CODE     = 0x33;

	private static final byte[] NULL_ARR           = new byte[] {nil};
	private static final byte[] NULL_ESCAPED_ARR   = new byte[] {nil, (byte)0xFF};
	private static final byte[] BYTES_ARR          = new byte[]{0x01};
	private static final byte[] STRING_ARR         = new byte[]{0x02};
	private static final byte[] NESTED_ARR         = new byte[]{0x05};
	private static final byte[] FALSE_ARR          = new byte[]{0x26};
	private static final byte[] TRUE_ARR           = new byte[]{0x27};
	private static final byte[] VERSIONSTAMP_ARR   = new byte[]{0x33};

	static {
		size_limits = new BigInteger[9];
		for(int i = 0; i < 9; i++) {
			size_limits[i] = (BigInteger.ONE).shiftLeft(i * 8).subtract(BigInteger.ONE);
		}
		UTF8 = Charset.forName("UTF-8");
		iterableComparator = new IterableComparator();
	}

	static class DecodeResult {
		final List<Object> values;
		int end;

		DecodeResult() {
			values = new ArrayList<>();
			end = 0;
		}

		void add(Object value, int end) {
			values.add(value);
			this.end = end;
		}
	}

	static class EncodeResult {
		final List<byte[]> encodedValues;
		int totalLength;
		int versionPos;

		EncodeResult(int capacity) {
			this.encodedValues = new ArrayList<>(capacity);
			totalLength = 0;
			versionPos = -1;
		}

		EncodeResult add(byte[] encoded, int versionPos) {
			if(versionPos >= 0 && this.versionPos >= 0) {
				throw new IllegalArgumentException("Multiple incomplete Versionstamps included in Tuple");
			}
			encodedValues.add(encoded);
			totalLength += encoded.length;
			this.versionPos = versionPos;
			return this;
		}

		EncodeResult add(byte[] encoded) {
			encodedValues.add(encoded);
			totalLength += encoded.length;
			return this;
		}
	}

	static int byteLength(byte[] bytes) {
		for(int i = 0; i < bytes.length; i++) {
			if(bytes[i] == 0x00) continue;
			return bytes.length - i;
		}
		return 0;
	}

	/**
	 * Takes the Big-Endian byte representation of a floating point number and adjusts
	 * it so that it sorts correctly. For encoding, if the sign bit is 1 (the number
	 * is negative), then we need to flip all of the bits; otherwise, just flip the
	 * sign bit. For decoding, if the sign bit is 0 (the number is negative), then
	 * we also need to flip all of the bits; otherwise, just flip the sign bit.
	 * This will mutate in place the given array.
	 *
	 * @param bytes Big-Endian IEEE encoding of a floating point number
	 * @param start the (zero-indexed) first byte in the array to mutate
	 * @param encode <code>true</code> if we encoding the float and <code>false</code> if we are decoding
	 * @return the encoded {@code byte[]}
	 */
	static byte[] floatingPointCoding(byte[] bytes, int start, boolean encode) {
		if(encode && (bytes[start] & (byte)0x80) != (byte)0x00) {
			for(int i = start; i < bytes.length; i++) {
				bytes[i] = (byte) (bytes[i] ^ 0xff);
			}
		} else if(!encode && (bytes[start] & (byte)0x80) != (byte)0x80) {
			for(int i = start; i < bytes.length; i++) {
				bytes[i] = (byte) (bytes[i] ^ 0xff);
			}
		} else {
			bytes[start] = (byte) (0x80 ^ bytes[start]);
		}

		return bytes;
	}

	static byte[] join(List<byte[]> items) {
		return ByteArrayUtil.join(null, items);
	}

	private static void adjustVersionPosition300(byte[] packed, int delta) {
		int offsetOffset = packed.length - Short.BYTES;
		ByteBuffer buffer = ByteBuffer.wrap(packed, offsetOffset, Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
		int versionPosition = buffer.getShort() + delta;
		if(versionPosition > 0xffff) {
			throw new IllegalArgumentException("Tuple has incomplete version at position " + versionPosition + " which is greater than the maximum " + 0xffff);
		}
		if(versionPosition < 0) {
			throw new IllegalArgumentException("Tuple has an incomplete version at a negative position");
		}
		buffer.position(offsetOffset);
		buffer.putShort((short)versionPosition);
	}

	private static void adjustVersionPosition520(byte[] packed, int delta) {
		int offsetOffset = packed.length - Integer.BYTES;
		ByteBuffer buffer = ByteBuffer.wrap(packed, offsetOffset, Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
		int versionPosition = buffer.getInt() + delta;
		if(versionPosition < 0) {
			throw new IllegalArgumentException("Tuple has an incomplete version at a negative position");
		}
		buffer.position(offsetOffset);
		buffer.putInt(versionPosition);
	}

	static void adjustVersionPosition(byte[] packed, int delta) {
		if(FDB.instance().getAPIVersion() < 520) {
			adjustVersionPosition300(packed, delta);
		}
		else {
			adjustVersionPosition520(packed, delta);
		}
	}

	static int getCodeFor(Object o) {
		if(o == null)
			return nil;
		if(o instanceof byte[])
			return BYTES_CODE;
		if(o instanceof String)
			return STRING_CODE;
		if(o instanceof Float)
			return FLOAT_CODE;
		if(o instanceof Double)
			return DOUBLE_CODE;
		if(o instanceof Boolean)
			return FALSE_CODE;
		if(o instanceof UUID)
			return UUID_CODE;
		if(o instanceof Number)
			return INT_ZERO_CODE;
		if(o instanceof Versionstamp)
			return VERSIONSTAMP_CODE;
		if(o instanceof List<?>)
			return NESTED_CODE;
		if(o instanceof Tuple)
			return NESTED_CODE;
		throw new IllegalArgumentException("Unsupported data type: " + o.getClass().getName());
	}

	static void encode(EncodeResult result, Object t, boolean nested) {
		if(t == null) {
			if(nested) {
				result.add(NULL_ESCAPED_ARR);
			}
			else {
				result.add(NULL_ARR);
			}
		}
		else if(t instanceof byte[])
			encode(result, (byte[]) t);
		else if(t instanceof String)
			encode(result, (String)t);
		else if(t instanceof BigInteger)
			encode(result, (BigInteger)t);
		else if(t instanceof Float)
			encode(result, (Float)t);
		else if(t instanceof Double)
			encode(result, (Double)t);
		else if(t instanceof Boolean)
			encode(result, (Boolean)t);
		else if(t instanceof UUID)
			encode(result, (UUID)t);
		else if(t instanceof Number)
			encode(result, ((Number)t).longValue());
		else if(t instanceof Versionstamp)
			encode(result, (Versionstamp)t);
		else if(t instanceof List<?>)
			encode(result, (List<?>)t);
		else if(t instanceof Tuple)
			encode(result, ((Tuple)t).getItems());
		else
			throw new IllegalArgumentException("Unsupported data type: " + t.getClass().getName());
	}

	static void encode(EncodeResult result, Object t) {
		encode(result, t, false);
	}

	static void encode(EncodeResult result, byte[] bytes) {
		byte[] escaped = ByteArrayUtil.replace(bytes, NULL_ARR, NULL_ESCAPED_ARR);
		result.add(BYTES_ARR).add(escaped).add(NULL_ARR);
	}

	static void encode(EncodeResult result, String s) {
		byte[] escaped = ByteArrayUtil.replace(s.getBytes(UTF8), NULL_ARR, NULL_ESCAPED_ARR);
		result.add(STRING_ARR).add(escaped).add(NULL_ARR);
	}

	static void encode(EncodeResult result, BigInteger i) {
		//System.out.println("Encoding integral " + i);
		if(i.equals(BigInteger.ZERO)) {
			result.add(new byte[]{INT_ZERO_CODE});
			return;
		}
		byte[] bytes = i.toByteArray();
		if(i.compareTo(BigInteger.ZERO) > 0) {
			if(i.compareTo(size_limits[size_limits.length-1]) > 0) {
				int length = byteLength(bytes);
				if(length > 0xff) {
					throw new IllegalArgumentException("BigInteger magnitude is too large (more than 255 bytes)");
				}
				byte[] intBytes = new byte[length + 2];
				intBytes[0] = POS_INT_END;
				intBytes[1] = (byte)(length);
				System.arraycopy(bytes, bytes.length - length, intBytes, 2, length);
				result.add(intBytes);
			}
			else {
				int n = ByteArrayUtil.bisectLeft(size_limits, i);
				assert n <= size_limits.length;
				//byte[] bytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(i).array();
				//System.out.println("  -- integral has 'n' of " + n + " and output bytes of " + bytes.length);
				byte[] intBytes = new byte[n + 1];
				intBytes[0] = (byte) (INT_ZERO_CODE + n);
				System.arraycopy(bytes, bytes.length - n, intBytes, 1, n);
				result.add(intBytes);
			}
		}
		else {
			if(i.negate().compareTo(size_limits[size_limits.length - 1]) > 0) {
				int length = byteLength(i.negate().toByteArray());
				if (length > 0xff) {
					throw new IllegalArgumentException("BigInteger magnitude is too large (more than 255 bytes)");
				}
				BigInteger offset = BigInteger.ONE.shiftLeft(length * 8).subtract(BigInteger.ONE);
				byte[] adjusted = i.add(offset).toByteArray();
				byte[] intBytes = new byte[length + 2];
				intBytes[0] = NEG_INT_START;
				intBytes[1] = (byte) (length ^ 0xff);
				if (adjusted.length >= length) {
					System.arraycopy(adjusted, adjusted.length - length, intBytes, 2, length);
				} else {
					Arrays.fill(intBytes, 2, intBytes.length - adjusted.length, (byte) 0x00);
					System.arraycopy(adjusted, 0, intBytes, intBytes.length - adjusted.length, adjusted.length);
				}
				result.add(intBytes);
			}
			else {
				int n = ByteArrayUtil.bisectLeft(size_limits, i.negate());

				assert n >= 0 && n < size_limits.length; // can we do this? it seems to be required for the following statement

				long maxv = size_limits[n].add(i).longValue();
				byte[] adjustedBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(maxv).array();
				byte[] intBytes = new byte[n + 1];
				intBytes[0] = (byte) (20 - n);
				System.arraycopy(adjustedBytes, adjustedBytes.length - n, intBytes, 1, n);
				result.add(intBytes);
			}
		}
	}

	static void encode(EncodeResult result, Integer i) {
		encode(result, i.longValue());
	}

	static void encode(EncodeResult result, long i) {
		encode(result, BigInteger.valueOf(i));
	}

	static void encode(EncodeResult result, Float f) {
		byte[] floatBytes = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).put(FLOAT_CODE).putFloat(f).array();
		floatingPointCoding(floatBytes, 1, true);
		result.add(floatBytes);
	}

	static void encode(EncodeResult result, Double d) {
		byte[] doubleBytes = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN).put(DOUBLE_CODE).putDouble(d).array();
		floatingPointCoding(doubleBytes, 1, true);
		result.add(doubleBytes);
	}

	static void encode(EncodeResult result, Boolean b) {
		if(b) {
			result.add(TRUE_ARR);
		}
		else {
			result.add(FALSE_ARR);
		}
	}

	static void encode(EncodeResult result, UUID uuid) {
		byte[] uuidBytes = ByteBuffer.allocate(17).put(UUID_CODE).order(ByteOrder.BIG_ENDIAN)
				.putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits())
				.array();
		result.add(uuidBytes);
	}

	static void encode(EncodeResult result, Versionstamp v) {
		result.add(VERSIONSTAMP_ARR);
		if(v.isComplete()) {
			result.add(v.getBytes());
		}
		else {
			result.add(v.getBytes(), result.totalLength);
		}
	}

	static void encode(EncodeResult result, List<?> value) {
		result.add(NESTED_ARR);
		for(Object t : value) {
			encode(result, t, true);
		}
		result.add(NULL_ARR);
	}

	static void decode(DecodeResult result, byte[] rep, int pos, int last) {
		//System.out.println("Decoding '" + ArrayUtils.printable(rep) + "' at " + pos);

		// SOMEDAY: codes over 127 will be a problem with the signed Java byte mess
		int code = rep[pos];
		int start = pos + 1;
		if(code == nil) {
			result.add(null, start);
		}
		else if(code == BYTES_CODE) {
			int end = ByteArrayUtil.findTerminator(rep, (byte)0x0, (byte)0xff, start, last);
			//System.out.println("End of byte string: " + end);
			byte[] range = ByteArrayUtil.replace(rep, start, end - start, NULL_ESCAPED_ARR, new byte[] { nil });
			//System.out.println(" -> byte string contents: '" + ArrayUtils.printable(range) + "'");
			result.add(range, end + 1);
		}
		else if(code == STRING_CODE) {
			int end = ByteArrayUtil.findTerminator(rep, (byte)0x0, (byte)0xff, start, last);
			//System.out.println("End of UTF8 string: " + end);
			byte[] stringBytes = ByteArrayUtil.replace(rep, start, end - start, NULL_ESCAPED_ARR, new byte[] { nil });
			String str = new String(stringBytes, UTF8);
			//System.out.println(" -> UTF8 string contents: '" + str + "'");
			result.add(str, end + 1);
		}
		else if(code == FLOAT_CODE) {
			byte[] resBytes = Arrays.copyOfRange(rep, start, start+4);
			floatingPointCoding(resBytes, 0, false);
			float res = ByteBuffer.wrap(resBytes).order(ByteOrder.BIG_ENDIAN).getFloat();
			result.add(res, start + Float.BYTES);
		}
		else if(code == DOUBLE_CODE) {
			byte[] resBytes = Arrays.copyOfRange(rep, start, start+8);
			floatingPointCoding(resBytes, 0, false);
			double res = ByteBuffer.wrap(resBytes).order(ByteOrder.BIG_ENDIAN).getDouble();
			result.add(res, start + Double.BYTES);
		}
		else if(code == FALSE_CODE) {
			result.add(false, start);
		}
		else if(code == TRUE_CODE) {
			result.add(true, start);
		}
		else if(code == UUID_CODE) {
			ByteBuffer bb = ByteBuffer.wrap(rep, start, 16).order(ByteOrder.BIG_ENDIAN);
			long msb = bb.getLong();
			long lsb = bb.getLong();
			result.add(new UUID(msb, lsb), start + 16);
		}
		else if(code == POS_INT_END) {
			int n = rep[start] & 0xff;
			BigInteger res = new BigInteger(ByteArrayUtil.join(new byte[]{0x00}, Arrays.copyOfRange(rep, start+1, start+n+1)));
			result.add(res, start + n + 1);
		}
		else if(code == NEG_INT_START) {
			int n = (rep[start] ^ 0xff) & 0xff;
			BigInteger origValue = new BigInteger(ByteArrayUtil.join(new byte[]{0x00}, Arrays.copyOfRange(rep, start+1, start+n+1)));
			BigInteger offset = BigInteger.ONE.shiftLeft(n*8).subtract(BigInteger.ONE);
			result.add(origValue.subtract(offset), start + n + 1);
		}
		else if(code > NEG_INT_START && code < POS_INT_END) {
			// decode a long
			byte[] longBytes = new byte[9];
			boolean upper = code >= INT_ZERO_CODE;
			int n = upper ? code - 20 : 20 - code;
			int end = start + n;

			if(rep.length < end) {
				throw new RuntimeException("Invalid tuple (possible truncation)");
			}

			System.arraycopy(rep, start, longBytes, longBytes.length-n, n);
			if (!upper)
				for(int i=longBytes.length-n; i<longBytes.length; i++)
					longBytes[i] = (byte)(longBytes[i] ^ 0xff);

			BigInteger val = new BigInteger(longBytes);
			if (!upper) val = val.negate();

			// Convert to long if in range -- otherwise, leave as BigInteger.
			if (val.compareTo(BigInteger.valueOf(Long.MIN_VALUE))<0||
				val.compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0) {
				// This can occur if the thing can be represented with 8 bytes but not
				// the right sign information.
				result.add(val, end);
			} else {
				result.add(val.longValue(), end);
			}
		}
		else if(code == VERSIONSTAMP_CODE) {
			Versionstamp val = Versionstamp.fromBytes(Arrays.copyOfRange(rep, start, start + Versionstamp.LENGTH));
			result.add(val, start + Versionstamp.LENGTH);
		}
		else if(code == NESTED_CODE) {
			DecodeResult subResult = new DecodeResult();
			int endPos = start;
			while(endPos < rep.length) {
				if(rep[endPos] == nil) {
					if(endPos + 1 < rep.length && rep[endPos+1] == (byte)0xff) {
						subResult.add(null, endPos + 2);
						endPos += 2;
					} else {
						endPos += 1;
						break;
					}
				} else {
					decode(subResult, rep, endPos, last);
					endPos = subResult.end;
				}
			}
			result.add(subResult.values, endPos);
		}
		else {
			throw new IllegalArgumentException("Unknown tuple data type " + code + " at index " + pos);
		}
	}

	static int compareSignedBigEndian(byte[] arr1, byte[] arr2) {
		if(arr1[0] < 0 && arr2[0] < 0) {
			return -1 * ByteArrayUtil.compareUnsigned(arr1, arr2);
		} else if(arr1[0] < 0) {
			return -1;
		} else if(arr2[0] < 0) {
			return 1;
		} else {
			return ByteArrayUtil.compareUnsigned(arr1, arr2);
		}
	}

	static int compareItems(Object item1, Object item2) {
		int code1 = TupleUtil.getCodeFor(item1);
		int code2 = TupleUtil.getCodeFor(item2);

		if(code1 != code2) {
			return Integer.compare(code1, code2);
		}

		if(code1 == nil) {
			// All null's are equal. (Some may be more equal than others.)
			return 0;
		}
		if(code1 == BYTES_CODE) {
			return ByteArrayUtil.compareUnsigned((byte[])item1, (byte[])item2);
		}
		if(code1 == STRING_CODE) {
			return ByteArrayUtil.compareUnsigned(((String)item1).getBytes(UTF8), ((String)item2).getBytes(UTF8));
		}
		if(code1 == INT_ZERO_CODE) {
			BigInteger bi1;
			if(item1 instanceof BigInteger) {
				bi1 = (BigInteger)item1;
			} else {
				bi1 = BigInteger.valueOf(((Number)item1).longValue());
			}
			BigInteger bi2;
			if(item2 instanceof BigInteger) {
				bi2 = (BigInteger)item2;
			} else {
				bi2 = BigInteger.valueOf(((Number)item2).longValue());
			}
			return bi1.compareTo(bi2);
		}
		if(code1 == DOUBLE_CODE) {
			// This is done over vanilla double comparison basically to handle NaN
			// sorting correctly.
			byte[] dBytes1 = ByteBuffer.allocate(8).putDouble((Double)item1).array();
			byte[] dBytes2 = ByteBuffer.allocate(8).putDouble((Double)item2).array();
			return compareSignedBigEndian(dBytes1, dBytes2);
		}
		if(code1 == FLOAT_CODE) {
			// This is done for the same reason that double comparison is done
			// that way.
			byte[] fBytes1 = ByteBuffer.allocate(4).putFloat((Float)item1).array();
			byte[] fBytes2 = ByteBuffer.allocate(4).putFloat((Float)item2).array();
			return compareSignedBigEndian(fBytes1, fBytes2);
		}
		if(code1 == FALSE_CODE) {
			return Boolean.compare((Boolean)item1, (Boolean)item2);
		}
		if(code1 == UUID_CODE) {
			// Java UUID.compareTo is signed, so we have to used the unsigned methods.
			UUID uuid1 = (UUID)item1;
			UUID uuid2 = (UUID)item2;
			int cmp1 = Long.compareUnsigned(uuid1.getMostSignificantBits(), uuid2.getMostSignificantBits());
			if(cmp1 != 0)
				return cmp1;
			return Long.compareUnsigned(uuid1.getLeastSignificantBits(), uuid2.getLeastSignificantBits());
		}
		if(code1 == VERSIONSTAMP_CODE) {
			return ((Versionstamp)item1).compareTo((Versionstamp)item2);
		}
		if(code1 == NESTED_CODE) {
			return iterableComparator.compare((Iterable<?>)item1, (Iterable<?>)item2);
		}
		throw new IllegalArgumentException("Unknown tuple data type: " + item1.getClass());
	}

	static List<Object> unpack(byte[] bytes, int start, int length) {
		DecodeResult decodeResult = new DecodeResult();
		int pos = start;
		int end = start + length;
		while(pos < end) {
			decode(decodeResult, bytes, pos, end);
			pos = decodeResult.end;
		}
		return decodeResult.values;
	}

	static void encodeAll(EncodeResult result, List<Object> items, byte[] prefix) {
		if(prefix != null) {
			result.add(prefix);
		}
		for(Object t : items) {
			encode(result, t);
		}
		//System.out.println("Joining whole tuple...");
	}

	static byte[] pack(List<Object> items, byte[] prefix) {
		EncodeResult result = new EncodeResult(2 * items.size() + (prefix == null ? 0 : 1));
		encodeAll(result, items, prefix);
		if(result.versionPos >= 0) {
			throw new IllegalArgumentException("Incomplete Versionstamp included in vanilla tuple packInternal");
		} else {
			return ByteArrayUtil.join(null, result.encodedValues);
		}
	}

	static byte[] packWithVersionstamp(List<Object> items, byte[] prefix) {
		EncodeResult result = new EncodeResult(2 * items.size() + (prefix == null ? 1 : 2));
		encodeAll(result, items, prefix);
		if(result.versionPos < 0) {
			throw new IllegalArgumentException("No incomplete Versionstamp included in tuple packInternal with versionstamp");
		} else {
			if(result.versionPos > 0xffff) {
				throw new IllegalArgumentException("Tuple has incomplete version at position " + result.versionPos + " which is greater than the maximum " + 0xffff);
			}
			if (FDB.instance().getAPIVersion() < 520) {
				result.add(ByteBuffer.allocate(Short.BYTES).order(ByteOrder.LITTLE_ENDIAN).putShort((short)result.versionPos).array());
			} else {
				result.add(ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(result.versionPos).array());
			}
			return ByteArrayUtil.join(null, result.encodedValues);
		}
	}

	static boolean hasIncompleteVersionstamp(Stream<?> items) {
		return items.anyMatch(item -> {
			if(item == null) {
				return false;
			} else if(item instanceof Versionstamp) {
				return !((Versionstamp) item).isComplete();
			} else if(item instanceof Tuple) {
				return hasIncompleteVersionstamp(((Tuple) item).stream());
			} else if(item instanceof Collection<?>) {
				return hasIncompleteVersionstamp(((Collection) item).stream());
			} else {
				return false;
			}
		});
	}

	public static void main(String[] args) {
		try {
			byte[] bytes = pack(Collections.singletonList(4), null);
			DecodeResult result = new DecodeResult();
			decode(result, bytes, 0, bytes.length);
			int val = (int)result.values.get(0);
			assert 4 == val;
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("Error " + e.getMessage());
		}

		try {
			byte[] bytes = pack(Collections.singletonList("\u021Aest \u0218tring"), null);
			DecodeResult result = new DecodeResult();
			decode(result, bytes, 0, bytes.length);
			String string = (String)result.values.get(0);
			System.out.println("contents -> " + string);
			assert "\u021Aest \u0218tring".equals(string);
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("Error " + e.getMessage());
		}

		/*Object[] a = new Object[] { "\u0000a", -2, "b\u0001", 12345, ""};
		List<Object> o = Arrays.asList(a);
		byte[] packed = packInternal( o, null );
		System.out.println("packed length: " + packed.length);
		o = unpack( packed, 0, packed.length );
		System.out.println("unpacked elements: " + o);
		for(Object obj : o)
			System.out.println(" -> type: " + obj.getClass().getName());*/
	}
	private TupleUtil() {}
}
