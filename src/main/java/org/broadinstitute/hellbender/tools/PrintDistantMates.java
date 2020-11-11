package org.broadinstitute.hellbender.tools;

import htsjdk.samtools.Cigar;
import htsjdk.samtools.TextCigarCodec;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.engine.FeatureContext;
import org.broadinstitute.hellbender.engine.GATKPath;
import org.broadinstitute.hellbender.engine.ReadWalker;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.engine.filters.ReadFilter;
import org.broadinstitute.hellbender.engine.filters.ReadFilterLibrary;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.read.SAMFileGATKReadWriter;
import picard.cmdline.programgroups.ReadDataManipulationProgramGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@CommandLineProgramProperties(
        summary = "Prints reads that have distant mates using the mate's alignment information.  Yes, this is weird, but it helps in processing pairs together (see PairWalker).",
        oneLineSummary = "Print reads with distant mates using the mate's alignment.",
        programGroup = ReadDataManipulationProgramGroup.class
)
@DocumentedFeature
public class PrintDistantMates extends ReadWalker {
    @Argument(fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME,
            shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME,
            doc="Write output to this file")
    public GATKPath output;

    private SAMFileGATKReadWriter outputWriter;
    private Map<String, GATKRead> pendingReads;
    private static final String MATE_CIGAR_TAG = "MC";
    private static final String ORIGINAL_CIGAR = "OC";

    @Override public List<ReadFilter> getDefaultReadFilters() {
        final List<ReadFilter> readFilters = new ArrayList<>(super.getDefaultReadFilters());
        readFilters.add(ReadFilterLibrary.PAIRED);
        readFilters.add(ReadFilterLibrary.PRIMARY_LINE);
        readFilters.add(ReadFilterLibrary.NOT_DUPLICATE);
        readFilters.add(ReadFilterLibrary.MATE_DISTANT);
        return readFilters;
    }

    @Override
    public boolean requiresReads() { return true; }

    @Override
    public void onTraversalStart() {
        outputWriter = createSAMWriter(output, false);
        pendingReads = new HashMap<>(20000000);
    }

    @Override
    public void apply( final GATKRead read,
                       final ReferenceContext referenceContext,
                       final FeatureContext featureContext ) {
        final String mateCigarString = read.getAttributeAsString(MATE_CIGAR_TAG);
        if ( mateCigarString != null ) {
            final Cigar mateCigar = TextCigarCodec.decode(mateCigarString);
            outputWriter.addRead(doDistantMateAlterations(read, mateCigar));
        } else {
            pendingReads.compute(read.getName(), (name, mate) -> {
                if ( mate == null ) {
                    return read;
                }
                outputWriter.addRead(doDistantMateAlterations(read, mate.getCigar()));
                outputWriter.addRead(doDistantMateAlterations(mate, read.getCigar()));
                return null;
            });
        }
    }

    @Override
    public void closeTool() {
        if ( outputWriter != null ) {
            outputWriter.close();
        }
    }

    public static GATKRead doDistantMateAlterations( final GATKRead read, final Cigar mateCigar ) {
        final GATKRead copy = read.copy();
        copy.setPosition(read.getMateContig(), read.getMateStart());
        copy.setMatePosition(read.getContig(), read.getStart());
        final int mateReadLength = mateCigar.getReadLength();
        final int readLength = read.getLength();
        if ( mateReadLength == readLength ) {
            copy.setCigar(mateCigar);
        } else {
            final int mateAlignLength = mateCigar.getReferenceLength();
            copy.setCigar(bogusCigar(mateAlignLength, readLength));
        }
        copy.setAttribute(ORIGINAL_CIGAR, read.getCigar().toString());
        return copy;
    }

    public static boolean isDistantMate( final GATKRead read ) {
        return read.hasAttribute(ORIGINAL_CIGAR);
    }

    public static GATKRead undoDistantMateAlterations( final GATKRead read ) {
        final String originalCigar = read.getAttributeAsString(ORIGINAL_CIGAR);
        if ( originalCigar == null ) return read;
        final GATKRead copy = read.copy();
        copy.setPosition(read.getMateContig(), read.getMateStart());
        copy.setMatePosition(read.getContig(), read.getStart());
        copy.setCigar(originalCigar);
        copy.clearAttribute(ORIGINAL_CIGAR);
        return copy;
    }

    private static String bogusCigar( final int alignLength, final int readLength ) {
        final int lengthDiff = alignLength - readLength;
        if ( lengthDiff == 0 ) {
            return readLength +"M";
        }
        if ( lengthDiff > 0 ) {
            return "1M" + lengthDiff + "D" + (readLength - 1) + "M";
        }
        return "1M" + -lengthDiff + "I" + (alignLength - 1) + "M";
    }
}
