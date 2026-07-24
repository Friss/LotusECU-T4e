//@category Lotus ECU
//@description Export primary non-default symbols as address, type, source, and name.

import java.io.File;
import java.io.PrintWriter;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Data;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;

public class ExportPrimarySymbols extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: ExportPrimarySymbols.java <output.tsv>");
        }

        try (PrintWriter writer = new PrintWriter(new File(args[0]))) {
            writer.println("address\ttype\tsource\tname\tparent\tdatatype\tlength");
            SymbolIterator symbols = currentProgram.getSymbolTable().getAllSymbols(true);
            while (symbols.hasNext() && !monitor.isCancelled()) {
                Symbol symbol = symbols.next();
                if (!symbol.isPrimary() || symbol.getSource() == SourceType.DEFAULT ||
                        !symbol.getAddress().isMemoryAddress()) {
                    continue;
                }
                Data data = currentProgram.getListing().getDefinedDataAt(symbol.getAddress());
                writer.printf("%s\t%s\t%s\t%s\t%s\t%s\t%d%n", symbol.getAddress(),
                    symbol.getSymbolType(), symbol.getSource(), sanitize(symbol.getName()),
                    sanitize(symbol.getParentNamespace().getName(true)),
                    data == null ? "" : sanitize(data.getDataType().getName()),
                    data == null ? 0 : data.getLength());
            }
        }
        println("Wrote " + args[0]);
    }

    private String sanitize(String value) {
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
