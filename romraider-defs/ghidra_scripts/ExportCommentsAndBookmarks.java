//@category Lotus ECU
//@description Export comments, function comments, and bookmarks without changing the program.

import java.io.File;
import java.io.PrintWriter;
import java.util.Iterator;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressIterator;
import ghidra.program.model.listing.Bookmark;
import ghidra.program.model.listing.CodeUnit;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Listing;

public class ExportCommentsAndBookmarks extends GhidraScript {
    private static final String[] COMMENT_TYPES = {
        "EOL", "PRE", "POST", "PLATE", "REPEATABLE"
    };

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 1) {
            throw new IllegalArgumentException(
                "usage: ExportCommentsAndBookmarks.java <output.tsv>");
        }

        try (PrintWriter writer = new PrintWriter(new File(args[0]))) {
            writer.println("address\tkind\tsubtype\ttext");
            Listing listing = currentProgram.getListing();
            AddressIterator addresses =
                listing.getCommentAddressIterator(currentProgram.getMemory(), true);
            while (addresses.hasNext() && !monitor.isCancelled()) {
                Address address = addresses.next();
                for (int type = CodeUnit.EOL_COMMENT; type <= CodeUnit.REPEATABLE_COMMENT; type++) {
                    String comment = listing.getComment(type, address);
                    if (comment != null && !comment.isEmpty()) {
                        writer.printf("%s\tCOMMENT\t%s\t%s%n", address,
                            COMMENT_TYPES[type], sanitize(comment));
                    }
                }
            }

            FunctionIterator functions = currentProgram.getFunctionManager().getFunctions(true);
            while (functions.hasNext() && !monitor.isCancelled()) {
                Function function = functions.next();
                if (function.getComment() != null && !function.getComment().isEmpty()) {
                    writer.printf("%s\tFUNCTION\tCOMMENT\t%s%n", function.getEntryPoint(),
                        sanitize(function.getComment()));
                }
                if (function.getRepeatableComment() != null &&
                        !function.getRepeatableComment().isEmpty()) {
                    writer.printf("%s\tFUNCTION\tREPEATABLE\t%s%n", function.getEntryPoint(),
                        sanitize(function.getRepeatableComment()));
                }
            }

            Iterator<Bookmark> bookmarks =
                currentProgram.getBookmarkManager().getBookmarksIterator();
            while (bookmarks.hasNext() && !monitor.isCancelled()) {
                Bookmark bookmark = bookmarks.next();
                writer.printf("%s\tBOOKMARK\t%s/%s\t%s%n", bookmark.getAddress(),
                    sanitize(bookmark.getTypeString()), sanitize(bookmark.getCategory()),
                    sanitize(bookmark.getComment()));
            }
        }
        println("Wrote " + args[0]);
    }

    private String sanitize(String value) {
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
