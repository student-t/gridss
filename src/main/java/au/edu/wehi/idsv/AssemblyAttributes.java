package au.edu.wehi.idsv;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

import au.edu.wehi.idsv.sam.SamTags;
import au.edu.wehi.idsv.util.MessageThrottler;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.util.Log;

public class AssemblyAttributes {
	private static final Log log = Log.getInstance(AssemblyAttributes.class);
	private static final String ID_COMPONENT_SEPARATOR = " ";
	private final SAMRecord record;
	private HashSet<String> evidenceIDs = null;
	public static boolean isAssembly(SAMRecord record) {
		return record.getAttribute(SamTags.EVIDENCEID) != null;
	}
	public static boolean isUnanchored(SAMRecord record) {
		return record.hasAttribute(SamTags.UNANCHORED);
		//return Iterables.any(record.getCigar().getCigarElements(), ce -> ce.getOperator() == CigarOperator.X);
	}
	public static boolean isAssembly(DirectedEvidence record) {
		if (record instanceof SingleReadEvidence) {
			return isAssembly(((SingleReadEvidence)record).getSAMRecord());
		}
		return false;
	}
	public AssemblyAttributes(SAMRecord record) {
		this.record = record;
	}
	public AssemblyAttributes(SingleReadEvidence record) {
		this(record.getSAMRecord());
	}
	/**
	 * Determines whether the given record is part of the given assembly
	 *
	 * This method is a probabilistic method and it is possible for the record to return true
	 * when the record does not form part of the assembly breakend
	 *
	 * @param evidence
	 * @return true if the record is likely part of the breakend, false if definitely not
	 */
	public boolean isPartOfAssembly(DirectedEvidence e) {
		return getEvidenceIDs().contains(e.getEvidenceID());
	}
	public Collection<String> getEvidenceIDs() {
		if (evidenceIDs == null) {
			String encoded = record.getStringAttribute(SamTags.EVIDENCEID);
			if (encoded == null) {
				throw new IllegalStateException("Unable to get constituent evidenceIDs from assembly with evidence tracking disabled");
			}
			String[] ids = encoded.split(ID_COMPONENT_SEPARATOR);
			evidenceIDs = new HashSet<String>(Arrays.asList(ids));
			evidenceIDs.remove("");
		}
		return evidenceIDs;
	}
	public List<String> getOriginatingFragmentID() {
		String encoded = record.getStringAttribute(SamTags.ASSEMBLY_SUPPORTING_FRAGMENTS);
		if (encoded == null) {
			return ImmutableList.of();
		}
		return toList(encoded, ID_COMPONENT_SEPARATOR);
	}
	private static final List<String> toList(String str, String separator) {
		return Arrays.stream(str.split(ID_COMPONENT_SEPARATOR))
				.filter(s -> StringUtils.isNotEmpty(s))
				.collect(Collectors.toList());
	}
	/**
	 * Breakdown of DNA fragment support by category
	 * @param category
	 * @return
	 */
	public List<String> getOriginatingFragmentID(int category) {
		String encoded = record.getStringAttribute(SamTags.ASSEMBLY_SUPPORTING_FRAGMENTS);
		if (encoded == null) {
			return ImmutableList.of();
		}
		String[] categoryString = encoded.split(ID_COMPONENT_SEPARATOR + ID_COMPONENT_SEPARATOR);
		if (categoryString.length <= category) {
			return ImmutableList.of();
		}
		return Arrays.stream(categoryString[category].split(ID_COMPONENT_SEPARATOR))
				.filter(s -> StringUtils.isNotEmpty(s))
				.collect(Collectors.toList());
	}
	public static void annotateNonSupporting(ProcessingContext context, BreakpointSummary assemblyBreakpoint, SAMRecord record, Collection<DirectedEvidence> support) {
		int n = context.getCategoryCount();
		float[] nsrpQual = new float[n];
		float[] nsscQual = new float[n];
		int[] nsrpCount = new int[n];
		int[] nsscCount = new int[n];
		BreakpointSummary breakendWithMargin = (BreakpointSummary)context.getVariantCallingParameters().withMargin(assemblyBreakpoint);
		for (DirectedEvidence e : support) {
			int offset = ((SAMEvidenceSource)e.getEvidenceSource()).getSourceCategory();
			float qual = e.getBreakendQual();
			if (e instanceof NonReferenceReadPair) {
				if (breakendWithMargin != null && !breakendWithMargin.overlaps(e.getBreakendSummary())) {
					nsrpCount[offset]++;
					nsrpQual[offset] += qual;
				}
			} else if (e instanceof SingleReadEvidence) {
				if (breakendWithMargin != null && !breakendWithMargin.overlaps(e.getBreakendSummary())) {
					nsscCount[offset]++;
					nsscQual[offset] += qual;
				}
			} else {
				throw new NotImplementedException("Sanity check failure: not a read or a read pair.");
			}
		}
		record.setAttribute(SamTags.ASSEMBLY_NONSUPPORTING_READPAIR_COUNT, nsrpCount);
		record.setAttribute(SamTags.ASSEMBLY_NONSUPPORTING_SOFTCLIP_COUNT, nsscCount);
		record.setAttribute(SamTags.ASSEMBLY_NONSUPPORTING_READPAIR_QUAL, nsrpQual);
		record.setAttribute(SamTags.ASSEMBLY_NONSUPPORTING_SOFTCLIP_QUAL, nsscQual);
	}
	/**
	 * Annotates an assembly with summary information regarding the reads used to produce the assembly
	 */
	public static void annotateAssembly(ProcessingContext context, SAMRecord record, Collection<DirectedEvidence> support) {
		if (support == null) {
			if (!MessageThrottler.Current.shouldSupress(log, "assemblies with no support")) {
				log.error("No support for assembly " + record.getReadName());
			}
			support = Collections.emptyList();
		}
		int n = context.getCategoryCount();
		float[] rpQual = new float[n];
		float[] scQual = new float[n];
		int[] rpCount = new int[n];
		int[] rpMaxLen = new int[n];
		int[] scCount = new int[n];
		int[] scLenMax = new int[n];
		int[] scLenTotal = new int[n];
		int maxLocalMapq = 0;
		for (DirectedEvidence e : support) {
			assert(e != null);
			maxLocalMapq = Math.max(maxLocalMapq, e.getLocalMapq());
			int offset = ((SAMEvidenceSource)e.getEvidenceSource()).getSourceCategory();
			float qual = e.getBreakendQual();
			if (e instanceof NonReferenceReadPair) {
				rpCount[offset]++;
				rpQual[offset] += qual;
				rpMaxLen[offset] = Math.max(rpMaxLen[offset], ((NonReferenceReadPair)e).getNonReferenceRead().getReadLength());
			} else if (e instanceof SingleReadEvidence) {
				scCount[offset]++;
				scQual[offset] += qual;
				int clipLength = e.getBreakendSequence().length;
				scLenMax[offset] = Math.max(scLenMax[offset], clipLength);
				scLenTotal[offset] += clipLength;
			} else {
				throw new NotImplementedException("Sanity check failure: not a read or a read pair.");
			}
		}
		ensureUniqueEvidenceID(record.getReadName(), support);
		
		Map<Integer, List<DirectedEvidence>> evidenceByCategory = support.stream()
				.collect(Collectors.groupingBy(e -> ((SAMEvidenceSource)e.getEvidenceSource()).getSourceCategory()));
		String evidenceString = IntStream.range(0, evidenceByCategory.keySet().stream().mapToInt(x -> x).max().orElse(0) + 1)
			.mapToObj(i -> evidenceByCategory.get(i) == null ? "" : evidenceByCategory.get(i).stream()
					.map(e -> e.getEvidenceID())
					.distinct()
					.sorted()
					.collect(Collectors.joining(ID_COMPONENT_SEPARATOR)))
			.collect(Collectors.joining(ID_COMPONENT_SEPARATOR + ID_COMPONENT_SEPARATOR));
		String fragmentString = IntStream.range(0, evidenceByCategory.keySet().stream().mapToInt(x -> x).max().orElse(0) + 1)
			.mapToObj(i -> evidenceByCategory.get(i) == null ? "" : evidenceByCategory.get(i).stream()
					.flatMap(x -> x.getOriginatingFragmentID(i).stream())
					.distinct()
					.sorted()
					.collect(Collectors.joining(ID_COMPONENT_SEPARATOR)))
			.collect(Collectors.joining(ID_COMPONENT_SEPARATOR + ID_COMPONENT_SEPARATOR));
		record.setAttribute(SamTags.EVIDENCEID, evidenceString);
		record.setAttribute(SamTags.ASSEMBLY_SUPPORTING_FRAGMENTS, fragmentString);
		record.setAttribute(SamTags.ASSEMBLY_READPAIR_COUNT, rpCount);
		record.setAttribute(SamTags.ASSEMBLY_READPAIR_LENGTH_MAX, rpMaxLen);
		record.setAttribute(SamTags.ASSEMBLY_SOFTCLIP_COUNT, scCount);
		record.setAttribute(SamTags.ASSEMBLY_SOFTCLIP_CLIPLENGTH_MAX, scLenMax);
		record.setAttribute(SamTags.ASSEMBLY_SOFTCLIP_CLIPLENGTH_TOTAL, scLenTotal);
		record.setAttribute(SamTags.ASSEMBLY_READPAIR_QUAL, rpQual);
		record.setAttribute(SamTags.ASSEMBLY_SOFTCLIP_QUAL, scQual);
		record.setAttribute(SamTags.ASSEMBLY_STRAND_BIAS, (float)(support.size() == 0 ? 0.0 : (support.stream().mapToDouble(de -> de.getStrandBias()).sum() / support.size())));
		// TODO: proper mapq model
		record.setMappingQuality(maxLocalMapq);
		if (record.getMappingQuality() < context.getConfig().minMapq) {
			if (!MessageThrottler.Current.shouldSupress(log, "below minimum mapq")) {
				log.warn(String.format("Sanity check failure: %s has mapq below minimum", record.getReadName()));
			}
		}
	}
	private static boolean ensureUniqueEvidenceID(String assemblyName, Collection<DirectedEvidence> support) {
		boolean isUnique = true;
		Set<String> map = new HashSet<String>();
		for (DirectedEvidence id : support) {
			if (map.contains(id.getEvidenceID())) {
				if (!MessageThrottler.Current.shouldSupress(log, "duplicated evidenceIDs")) {
					log.error("Found evidenceID " + id.getEvidenceID() + " multiple times in assembly " + assemblyName);
				}
				isUnique = false;
			}
			map.add(id.getEvidenceID());
		}
		return isUnique;
	}
	private int asFilteredIntSum(String attr, List<Boolean> filter) {
		List<Integer> list = AttributeConverter.asIntList(record.getAttribute(attr));
		return Streams.zip(
				list.stream(),
				filter.stream(),
				(x, supported) -> (Integer)(((boolean) supported) ? x : 0))
			.mapToInt(x -> x).sum();
	}
	private float asFilteredFloatSum(String attr, List<Boolean> filter) {
		List<Double> list = AttributeConverter.asDoubleList(record.getAttribute(attr));
		return (float)Streams.zip(
				list.stream(),
				filter.stream(),
				(x, supported) -> (Double)(double)(((boolean) supported) ? x : 0))
			.mapToDouble(x -> x).sum();
	}
	public int getAssemblyTotalReadSupportCount() {
		return Streams.concat(
			AttributeConverter.asIntList(record.getAttribute(SamTags.ASSEMBLY_SOFTCLIP_COUNT)).stream(),
			AttributeConverter.asIntList(record.getAttribute(SamTags.ASSEMBLY_READPAIR_COUNT)).stream())
		.mapToInt(x -> x)
		.sum();
	}
	public int getAssemblySupportCount(List<Boolean> supportingCategories) {
		return getAssemblySupportCountSoftClip(supportingCategories) + getAssemblySupportCountReadPair(supportingCategories);
	}
	public int getAssemblySupportCountReadPair(int category) {
		return AttributeConverter.asIntListOffset(record.getAttribute(SamTags.ASSEMBLY_READPAIR_COUNT), category, 0);
	}
	public int getAssemblyReadPairLengthMax(int category) {
		return AttributeConverter.asIntListOffset(record.getAttribute(SamTags.ASSEMBLY_READPAIR_LENGTH_MAX), category, 0);
	}
	public int getAssemblySupportCountSoftClip(int category) {
		return AttributeConverter.asIntListOffset(record.getAttribute(SamTags.ASSEMBLY_SOFTCLIP_COUNT), category, 0);
	}
	public int getAssemblyNonSupportingReadPairCount(int category) {
		return AttributeConverter.asIntListOffset(record.getAttribute(SamTags.ASSEMBLY_NONSUPPORTING_READPAIR_COUNT), category, 0);
	}
	public int getAssemblyNonSupportingReadPairCount(List<Boolean> supportingCategories) {
		return asFilteredIntSum(SamTags.ASSEMBLY_NONSUPPORTING_READPAIR_COUNT, supportingCategories);
	}
	public int getAssemblyNonSupportingSoftClipCount(int category) {
		return AttributeConverter.asIntListOffset(record.getAttribute(SamTags.ASSEMBLY_NONSUPPORTING_SOFTCLIP_COUNT), category, 0);
	}
	public int getAssemblyNonSupportingSoftClipCount(List<Boolean> supportingCategories) {
		return asFilteredIntSum(SamTags.ASSEMBLY_NONSUPPORTING_SOFTCLIP_COUNT, supportingCategories);
	}
	public int getAssemblySoftClipLengthTotal(int category) {
		return AttributeConverter.asIntListOffset(record.getAttribute(SamTags.ASSEMBLY_SOFTCLIP_CLIPLENGTH_TOTAL), category, 0);
	}
	public int getAssemblySoftClipLengthMax(int category) {
		return AttributeConverter.asIntListOffset(record.getAttribute(SamTags.ASSEMBLY_SOFTCLIP_CLIPLENGTH_MAX), category, 0);
	}
	public float getAssemblySupportReadPairQualityScore(int category) {
		return (float)AttributeConverter.asDoubleListOffset(record.getAttribute(SamTags.ASSEMBLY_READPAIR_QUAL), category, 0);
	}
	public float getAssemblySupportSoftClipQualityScore(int category) {
		return (float)AttributeConverter.asDoubleListOffset(record.getAttribute(SamTags.ASSEMBLY_SOFTCLIP_QUAL), category, 0);
	}
	public float getAssemblyNonSupportingReadPairQualityScore(int category) {
		return (float)AttributeConverter.asDoubleListOffset(record.getAttribute(SamTags.ASSEMBLY_NONSUPPORTING_READPAIR_QUAL), category, 0);
	}
	public float getAssemblyNonSupportingReadPairQualityScore(List<Boolean> supportingCategories) {
		return asFilteredFloatSum(SamTags.ASSEMBLY_NONSUPPORTING_READPAIR_QUAL, supportingCategories);
	}
	public float getAssemblyNonSupportingSoftClipQualityScore(int category) {
		return (float)AttributeConverter.asDoubleListOffset(record.getAttribute(SamTags.ASSEMBLY_NONSUPPORTING_SOFTCLIP_QUAL), category, 0);
	}
	public float getAssemblyNonSupportingSoftClipQualityScore(List<Boolean> supportingCategories) {
		return asFilteredFloatSum(SamTags.ASSEMBLY_NONSUPPORTING_SOFTCLIP_QUAL, supportingCategories);
	}
	public int getAssemblySupportCountReadPair(List<Boolean> supportingCategories) {
		return asFilteredIntSum(SamTags.ASSEMBLY_READPAIR_COUNT, supportingCategories);
	}
	public int getAssemblyReadPairLengthMax() {
		return AttributeConverter.asIntList(record.getAttribute(SamTags.ASSEMBLY_READPAIR_LENGTH_MAX)).stream().mapToInt(x -> x).sum();
	}
	public int getAssemblySupportCountSoftClip(List<Boolean> supportingCategories) {
		return asFilteredIntSum(SamTags.ASSEMBLY_SOFTCLIP_COUNT, supportingCategories);
	}
	public int getAssemblySoftClipLengthTotal() {
		return AttributeConverter.asIntList(record.getAttribute(SamTags.ASSEMBLY_SOFTCLIP_CLIPLENGTH_TOTAL)).stream().mapToInt(x -> x).sum();
	}
	public int getAssemblySoftClipLengthMax() {
		return AttributeConverter.asIntList(record.getAttribute(SamTags.ASSEMBLY_SOFTCLIP_CLIPLENGTH_MAX)).stream().mapToInt(x -> x).sum();
	}
	public float getAssemblySupportReadPairQualityScore(List<Boolean> supportingCategories) {
		return asFilteredFloatSum(SamTags.ASSEMBLY_READPAIR_QUAL, supportingCategories);
	}
	public float getAssemblySupportSoftClipQualityScore(List<Boolean> supportingCategories) {
		return asFilteredFloatSum(SamTags.ASSEMBLY_SOFTCLIP_QUAL, supportingCategories);
	}
	public float getAssemblyNonSupportingQualityScore(List<Boolean> supportingCategories) {
		return asFilteredFloatSum(SamTags.ASSEMBLY_NONSUPPORTING_READPAIR_QUAL, supportingCategories) +
				asFilteredFloatSum(SamTags.ASSEMBLY_NONSUPPORTING_SOFTCLIP_QUAL, supportingCategories);
	}
	public int getAssemblyNonSupportingCount(List<Boolean> supportingCategories) {
		return asFilteredIntSum(SamTags.ASSEMBLY_NONSUPPORTING_READPAIR_COUNT, supportingCategories) +
				asFilteredIntSum(SamTags.ASSEMBLY_NONSUPPORTING_SOFTCLIP_COUNT, supportingCategories);
	}
	public BreakendDirection getAssemblyDirection() {
		Character c = (Character)record.getAttribute(SamTags.ASSEMBLY_DIRECTION);
		if (c == null) return null;
		return BreakendDirection.fromChar((char)c);
	}
	public double getStrandBias() {
		return AttributeConverter.asDouble(record.getAttribute(SamTags.ASSEMBLY_STRAND_BIAS), 0);
	}
}
