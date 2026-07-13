//@category Lotus ECU
//@description Export a compact, read-only inventory of a Ghidra program database.

import java.io.File;
import java.io.PrintWriter;

import ghidra.app.script.GhidraScript;
import ghidra.framework.options.Options;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;

public class InventoryProgram extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: InventoryProgram.java <output.txt>");
        }

        try (PrintWriter writer = new PrintWriter(new File(args[0]))) {
            writer.println("Program: " + currentProgram.getName());
            writer.println("Executable path: " + currentProgram.getExecutablePath());
            writer.println("Executable format: " + currentProgram.getExecutableFormat());
            writer.println("Language: " + currentProgram.getLanguageID());
            writer.println("Compiler: " + currentProgram.getCompilerSpec().getCompilerSpecID());
            writer.println("Image base: " + currentProgram.getImageBase());
            writer.println("Min address: " + currentProgram.getMinAddress());
            writer.println("Max address: " + currentProgram.getMaxAddress());
            writer.println("Instructions: " + currentProgram.getListing().getNumInstructions());
            writer.println("Defined data: " + currentProgram.getListing().getNumDefinedData());

            int functions = 0;
            int defaultFunctions = 0;
            int namedFunctions = 0;
            FunctionIterator functionIterator =
                currentProgram.getFunctionManager().getFunctions(true);
            while (functionIterator.hasNext()) {
                Function function = functionIterator.next();
                functions++;
                if (function.getSymbol().getSource() == SourceType.DEFAULT) {
                    defaultFunctions++;
                }
                else {
                    namedFunctions++;
                }
            }
            writer.println("Functions: " + functions);
            writer.println("Functions named by user/import/analysis: " + namedFunctions);
            writer.println("Functions with default names: " + defaultFunctions);

            int symbols = 0;
            int userSymbols = 0;
            int importedSymbols = 0;
            int analysisSymbols = 0;
            int defaultSymbols = 0;
            SymbolIterator symbolIterator = currentProgram.getSymbolTable().getAllSymbols(true);
            while (symbolIterator.hasNext()) {
                Symbol symbol = symbolIterator.next();
                symbols++;
                switch (symbol.getSource()) {
                    case USER_DEFINED: userSymbols++; break;
                    case IMPORTED: importedSymbols++; break;
                    case ANALYSIS: analysisSymbols++; break;
                    default: defaultSymbols++; break;
                }
            }
            writer.println("Symbols: " + symbols);
            writer.println("User-defined symbols: " + userSymbols);
            writer.println("Imported symbols: " + importedSymbols);
            writer.println("Analysis symbols: " + analysisSymbols);
            writer.println("Default symbols: " + defaultSymbols);

            writer.println();
            writer.println("MEMORY BLOCKS");
            for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
                writer.printf("%s %s..%s size=0x%x R=%s W=%s X=%s initialized=%s%n",
                    block.getName(), block.getStart(), block.getEnd(), block.getSize(),
                    block.isRead(), block.isWrite(), block.isExecute(), block.isInitialized());
            }

            writer.println();
            writer.println("PROGRAM INFO");
            Options info = currentProgram.getOptions(Program.PROGRAM_INFO);
            for (String optionName : info.getOptionNames()) {
                writer.println(optionName + ": " + info.getValueAsString(optionName));
            }

            writer.println();
            writer.println("REGISTER CONTEXT AT KEY ADDRESSES");
            for (long offset : new long[] { 0x40100L, 0x40524L, 0x59dd8L, 0x61628L }) {
                Address address = toAddr(offset);
                writer.print(address);
                for (String registerName : new String[] { "r2", "r13" }) {
                    Register register = currentProgram.getRegister(registerName);
                    writer.print(" " + registerName + "=");
                    writer.print(register == null ? "<missing>" :
                        currentProgram.getProgramContext().getValue(register, address, false));
                }
                writer.println();
            }
        }
        println("Wrote " + args[0]);
    }
}
