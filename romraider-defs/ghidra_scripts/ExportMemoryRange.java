//@category Lotus ECU
//@description Export an initialized memory range without changing the program database.

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;

public class ExportMemoryRange extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 3) {
            throw new IllegalArgumentException(
                "usage: ExportMemoryRange.java <output.bin> <start-hex> <length-hex>");
        }

        Address address = toAddr(Long.parseUnsignedLong(stripHexPrefix(args[1]), 16));
        long remaining = Long.parseUnsignedLong(stripHexPrefix(args[2]), 16);
        byte[] buffer = new byte[0x10000];

        try (BufferedOutputStream output =
                new BufferedOutputStream(new FileOutputStream(args[0]))) {
            while (remaining > 0) {
                int count = (int)Math.min(buffer.length, remaining);
                int read = currentProgram.getMemory().getBytes(address, buffer, 0, count);
                if (read != count) {
                    throw new IllegalStateException("short read at " + address);
                }
                output.write(buffer, 0, count);
                address = address.add(count);
                remaining -= count;
            }
        }
        println("Wrote " + args[0]);
    }

    private String stripHexPrefix(String value) {
        return value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
    }
}
