package au.edu.wehi.socrates;

import java.util.List;

import org.broadinstitute.variant.variantcontext.VariantContext;
import org.broadinstitute.variant.variantcontext.VariantContextBuilder;
import org.junit.Before;
import org.junit.Test;

import net.sf.samtools.SAMRecord;

import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;

import static org.junit.Assert.*;

public class DirectedEvidenceIteratorTest extends TestHelper {
	private List<SAMRecord> sv;
	private List<SAMRecord> mate;
	private List<SAMRecord> realigned;	
	private List<VariantContext> vcf;
	private List<DirectedEvidence> out;
	private int maxFragmentSize;
	@Before
	public void setup() {
		sv = Lists.newArrayList();
		mate = Lists.newArrayList();
		realigned = Lists.newArrayList();
		vcf = Lists.newArrayList();
		out = Lists.newArrayList();
		maxFragmentSize = 100;
	}
	public void go() {
		sv = sorted(sv);
		mate = mateSorted(mate);
		DirectedEvidenceIterator it = new DirectedEvidenceIterator(
				sv == null ? null : Iterators.peekingIterator(sv.iterator()),
				mate == null ? null : Iterators.peekingIterator(mate.iterator()),
				realigned == null ? null : Iterators.peekingIterator(realigned.iterator()),
				vcf == null ? null : Iterators.peekingIterator(vcf.iterator()),
				getSequenceDictionary(), maxFragmentSize);
		while (it.hasNext()) {
			out.add(it.next());
		}
		// check output is in order
		for (int i = 0; i < out.size() - 1; i++) {
			BreakpointLocation l0 = out.get(i).getBreakpointLocation();
			BreakpointLocation l1 = out.get(i).getBreakpointLocation();
			assertTrue(l0.referenceIndex < l1.referenceIndex || (l0.referenceIndex == l1.referenceIndex && l0.start <= l1.start));
		}
	}
	@Test
	public void should_pair_oea_with_mate() {
		sv.add(OEA(0, 1, "100M", true)[0]);
		mate.add(OEA(0, 1, "100M", true)[1]);
		go();
		assertEquals(1, out.size());
		assertTrue(out.get(0) instanceof NonReferenceReadPair);
	}
	@Test
	public void should_pair_dp_with_mate() {
		sv.add(DP(0, 1, "100M", true, 1, 1, "100M", true)[0]);
		mate.add(DP(0, 1, "100M", true, 1, 1, "100M", true)[1]);
		go();
		assertEquals(1, out.size());
		assertTrue(out.get(0) instanceof NonReferenceReadPair);
	}
	@Test
	public void should_match_sc_with_realign() {
		sv.add(withReadName("ReadName", Read(0, 1, "5S10M5S"))[0]);
		realigned.add(withReadName("0#1#bReadName", Read(0, 1, "5M"))[0]);
		realigned.add(withReadName("0#10#fReadName", Read(0, 1, "5M"))[0]);
		go();
		assertEquals(2, out.size());
		assertTrue(out.get(0) instanceof SoftClipEvidence);
		assertTrue(out.get(0).getBreakpointLocation() instanceof BreakpointInterval);
	}
	@Test
	public void should_return_sc() {
		SAMRecord r = Read(0, 1, "5S10M5S");
		sv.add(r);
		go();
		// forward and backward
		assertEquals(2, out.size());
		assertTrue(out.get(0) instanceof SoftClipEvidence);
		assertTrue(out.get(1) instanceof SoftClipEvidence);
	}
	@Test
	public void should_return_assembly() {
		DirectedBreakpointAssembly assembly = DirectedBreakpointAssembly.create(getSequenceDictionary(), SMALL_FA, "test", 0, 1, BreakpointDirection.Backward, B("A"), B("AA"), 5);
		vcf.add(new VariantContextBuilder(assembly).make());
		go();
		assertEquals(1, out.size());
		assertTrue(out.get(0) instanceof DirectedBreakpointAssembly);
	}
	@Test
	public void should_match_assembly_with_realign() {
		DirectedBreakpointAssembly assembly = DirectedBreakpointAssembly.create(getSequenceDictionary(), SMALL_FA, "test", 0, 1, BreakpointDirection.Backward, B("A"), B("AA"), 5);
		vcf.add(new VariantContextBuilder(assembly).make());
		SAMRecord r = Read(1, 10, "1M");
		r.setReadName("0#1#test-polyA:1-b");
		realigned.add(r);
		go();
		assertEquals(1, out.size());
		assertTrue(out.get(0) instanceof DirectedBreakpointAssembly);
		assertTrue(out.get(0).getBreakpointLocation() instanceof BreakpointInterval);
	}
	@Test
	public void should_ignore_non_sv_reads() {
		sv.add(RP(0, 1, 2, 1)[0]);
		sv.add(RP(0, 1, 2, 1)[1]);
		go();
		assertEquals(0, out.size());
	}
	@Test
	public void should_expect_mates_in_order() {
		sv.add(withReadName("DP", DP(0, 2, "100M", true, 1, 1, "100M", true))[0]);
		mate.add(withReadName("DP", DP(0, 2, "100M", true, 1, 1, "100M", true))[1]);
		sv.add(withReadName("OEA", OEA(0, 1, "100M", true))[0]);
		mate.add(withReadName("OEA", OEA(0, 1, "100M", true))[1]);
		go();
		assertEquals(2, out.size());
		assertTrue(out.get(0) instanceof NonReferenceReadPair);
		assertTrue(out.get(1) instanceof NonReferenceReadPair);
	}
	@Test
	public void should_allow_realign_in_order_at_same_position() {
		SAMRecord r = withReadName("ReadName", Read(0, 1, "5S10M5S"))[0];
		sv.add(r);
		SAMRecord f = withReadName("0#10#fReadName", Read(0, 1, "5M"))[0];
		SAMRecord b = withReadName("0#1#bReadName", Read(0, 1, "5M"))[0];
		DirectedBreakpointAssembly assembly = DirectedBreakpointAssembly.create(getSequenceDictionary(), SMALL_FA, "test", 0, 1, BreakpointDirection.Backward, B("A"), B("AA"), 5);
		vcf.add(new VariantContextBuilder(assembly).make());
		SAMRecord assemblyRealigned = withReadName("0#1#test-polyA:1-b", Read(1, 10, "1M"))[0];
		realigned.add(b);
		realigned.add(assemblyRealigned);
		realigned.add(f);
		go();
		assertEquals(3, out.size());
		assertTrue(out.get(0).getBreakpointLocation() instanceof BreakpointInterval);
		assertTrue(out.get(1).getBreakpointLocation() instanceof BreakpointInterval);
		assertTrue(out.get(2).getBreakpointLocation() instanceof BreakpointInterval);
	}
	@Test
	public void should_require_realign_in_call_position_order() {
		SAMRecord r = withReadName("ReadName", Read(0, 1, "5S10M5S"))[0];
		sv.add(r);
		DirectedBreakpointAssembly assembly = DirectedBreakpointAssembly.create(getSequenceDictionary(), SMALL_FA, "test", 0, 2, BreakpointDirection.Backward, B("A"), B("AA"), 5);
		vcf.add(new VariantContextBuilder(assembly).make());
		realigned.add(withReadName("0#1#bReadName", Read(0, 1, "5M"))[0]);
		realigned.add(withReadName("0#2#test-polyA:1-b", Read(1, 10, "1M"))[0]);
		realigned.add(withReadName("0#10#fReadName", Read(0, 1, "5M"))[0]);
		go();
		assertEquals(3, out.size());
		assertTrue(out.get(0) instanceof SoftClipEvidence); // backward
		assertTrue(out.get(1) instanceof DirectedBreakpointAssembly);
		assertTrue(out.get(2) instanceof SoftClipEvidence); // forward
		assertTrue(out.get(0).getBreakpointLocation() instanceof BreakpointInterval);
		assertTrue(out.get(1).getBreakpointLocation() instanceof BreakpointInterval);
		assertTrue(out.get(2).getBreakpointLocation() instanceof BreakpointInterval);
	}
}