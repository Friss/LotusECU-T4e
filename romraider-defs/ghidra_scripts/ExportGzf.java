//@category Lotus ECU
//@description Pack the current saved program database as a GZF archive.

import java.io.File;

import ghidra.app.script.GhidraScript;

public class ExportGzf extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: ExportGzf.java <output.gzf>");
        }
        currentProgram.getDomainFile().packFile(new File(args[0]), monitor);
        println("Wrote " + args[0]);
    }
}
