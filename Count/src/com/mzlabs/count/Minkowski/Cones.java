package com.mzlabs.count.Minkowski;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import com.mzlabs.count.ContingencyTableProblem;
import com.mzlabs.count.CountingProblem;
import com.mzlabs.count.divideandconquer.IntMat;
import com.mzlabs.count.divideandconquer.IntMat.RowDescription;
import com.mzlabs.count.util.IntVec;
import com.winvector.linalg.DenseVec;
import com.winvector.linalg.LinalgFactory;
import com.winvector.linalg.colt.ColtMatrix;
import com.winvector.lp.LPEQProb;
import com.winvector.lp.LPException;
import com.winvector.lp.LPSoln;
import com.winvector.lp.impl.RevisedSimplexSolver;

/**
 * For A x = b1, A x = b2 (x>=0, A totally unimodular)
 * find conditions such that { x | A x = b1, x>=0 } + { x | A x = b2, x>=0 } (Minkowski sum) =  { x | A x = b1 + b2, x>=0 }
 * From pp. 55-57 of John Mount Ph.D. thesis
 * @author johnmount
 *
 */
public final class Cones {
	private final RowDescription[] rowDescr; // map from Ain to full row-rank system
	private final int[][] A;  // full row-rank sub-row system
	private final IntVec[] checkVecs;

	
	/**
	 * @param A non-negative totally unimodular matrix with non-zero number of rows
	 */
	public Cones(final int[][] Ain) {
		if(Ain.length<=0) {
			throw new IllegalArgumentException("no rows");
		}
		for(final int[] Ai: Ain) {
			for(final int Aij: Ai) {
				if(Aij<0) {
					throw new IllegalArgumentException("negative entry");
				}
			}
		}
		rowDescr = IntMat.buildMapToCannon(Ain);
		A = IntMat.rowRestrict(Ain,rowDescr);
		final LinalgFactory<ColtMatrix> factory = ColtMatrix.factory;
		final int m = A.length;
		final int n = A[0].length;
		final SetStepper setStepper = new SetStepper(m,n);
		final int[] columnSelection = setStepper.first();
		final Set<IntVec> rows = new HashSet<IntVec>();
		do {
			final ColtMatrix mat = factory.newMatrix(m,m,false);
			for(int i=0;i<m;++i) {
				for(int j=0;j<m;++j) {
					mat.set(i,j,A[i][columnSelection[j]]);
				}
			}
			try {
				final ColtMatrix inv = mat.inverse();
				for(int i=0;i<m;++i) {
					final int[] v = new int[m];
					for(int j=0;j<m;++j) {
						final double vijR = inv.get(i,j);
						final int vij = (int)Math.round(vijR);
						if(Math.abs(vij-vijR)>1.0e-5) {
							throw new IllegalArgumentException("Matrix wasn't TU");
						}
						v[j] = vij;
					}
					rows.add(new IntVec(v));
				}
			} catch (Exception ex) {
			}
		} while(setStepper.next(columnSelection));
		checkVecs = rows.toArray(new IntVec[rows.size()]);
	}
	
	private IntVec rhsGroup(final int[] b) {
		final int ncheck = checkVecs.length;
		final int m = A.length;
		final int[] group = new int[ncheck];
		for(int i=0;i<ncheck;++i) {
			final IntVec row = checkVecs[i];
			int dot = 0;
			for(int j=0;j<m;++j) {
				dot += row.get(j)*b[j];
			}
			if(dot>0) {
				group[i] = 1;
			} else if(dot<0) {
				group[i] = -1;
			}
		}
		return new IntVec(group);
	}
	
	
	private static boolean zeroFree(final IntVec x) {
		final int n = x.dim();
		for(int i=0;i<n;++i) {
			if(x.get(i)==0) {
				return false;
			}
		}
		return true;
	}
	
	// just a heuristic, move up and wriggle
	private int[] getAConeInteriorPt(final int[] b) {
		final int n = b.length;
		final IntVec group = rhsGroup(b);
		if(zeroFree(group)) {
			return Arrays.copyOf(b,n);
		}
		final int[] bP = Arrays.copyOf(b,n);
		final Random rand = new Random(352253);
		while(true) {
			for(int i=0;i<n;++i) {
				bP[i] = 1000*b[i] + rand.nextInt(10);
			}
			final IntVec groupP = rhsGroup(bP);
			if(zeroFree(groupP)) {
				return bP;
			}
		}
	}
	
	private int[] placeWedgeBase(final IntVec group) throws LPException {
		final int ncheck = checkVecs.length;
		final int m = A.length;
		final int n = A[0].length;
		final LinalgFactory<ColtMatrix> factory = ColtMatrix.factory;
		if(!zeroFree(group)) {
			throw new IllegalArgumentException("on cone boundary");
		}
		final int totalRows = (m+1)*ncheck;
		final int probDim = m + totalRows;
		final int degree = n-m;
		final ColtMatrix mat = factory.newMatrix(totalRows,probDim,true);
		final double[] rhs = new double[totalRows];
		final double[] obj = new double[probDim];
		for(int i=0;i<m;++i) {
			obj[i] = 1.0;
		}
		int row = 0;
		int slackVar = m;
		// put in first set of conditions
		for(int i=0;i<ncheck;++i) {
			if(group.get(i)==0) {
				continue;
			}
			if(group.get(i)>0) {
				// expect checkVecs[i].x >= 0, so: checkVecs[i].x - slack = 0
				for(int j=0;j<m;++j) {
					mat.set(row,j,checkVecs[i].get(j));
				}
				mat.set(row,slackVar,-1.0);
			} else {
				// expect checkVecs[i].x <= 0 so: -checkVecs[i].x - slack = 0
				for(int j=0;j<m;++j) {
					mat.set(row,j,-checkVecs[i].get(j));
				}
				mat.set(row,slackVar,-1.0);
			}
			++slackVar;
			++row;
		}
		// put in additional wedge conditions
		for(int k=0;k<m;++k) {
			for(int i=0;i<ncheck;++i) {
				if(group.get(i)==0) {
					continue;
				}
				if(group.get(i)>0) {
					// expect checkVecs[i]*(x+degree*Ek) >= 0, so: checkVecs[i].x - slack = -degree*checkVecs[i][k];
					for(int j=0;j<m;++j) {
						mat.set(row,j,checkVecs[i].get(j));
					}
					mat.set(row,slackVar,-1.0);
					rhs[row] = -degree*checkVecs[i].get(k);
				} else {
					// expect checkVecs[i].(x+degree*Ek) <= 0 so: -checkVecs[i].x - slack =  degree*checkVecs[i][k]
					for(int j=0;j<m;++j) {
						mat.set(row,j,-checkVecs[i].get(j));
					}
					mat.set(row,slackVar,-1.0);
					rhs[row] = degree*checkVecs[i].get(k);
				}
				++slackVar;
				++row;
			}
		}
		if(row!=totalRows) {
			throw new IllegalStateException("wrong number of rows in LP");
		}
		if(slackVar!=probDim) {
			throw new IllegalStateException("wrong number of variables in LP");
		}
		final LPEQProb prob = new LPEQProb(mat.columnMatrix(),rhs,new DenseVec(obj));
		final RevisedSimplexSolver solver = new RevisedSimplexSolver();
		final LPSoln soln = solver.solve(prob, null, 1.0e-5, 1000, factory);
		final int[] base = new int[m];
		for(int i=0;i<m;++i) {
			base[i] = (int)Math.round(soln.primalSolution.get(i));
		}
		// base only guaranteed to be consistent if there were no zeros in the group key
		return base;
	}
	
	public static boolean compatibleSigns(final IntVec a, final IntVec b) {
		final int n = a.dim();
		for(int i=0;i<n;++i) {
			if(a.get(i)*b.get(i)<0) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 
	 * @param bIn vector with no non-positive entries compatible with original linear system
	 * @return a new system (in reduced rank notation) that would allow us to infer a cone polynomial
	 * @throws LPException
	 */
	public int[] buildConeWedge(final int[] bIn) throws LPException {
		if(bIn.length!=rowDescr.length) {
			throw new IllegalArgumentException("wrong dimension b");
		}
		for(final int bi: bIn) {
			if(bi<=0) {
				throw new IllegalArgumentException("b wasn't positive");
			}
		}
		if(!IntMat.checkImpliedEntries(rowDescr,bIn)) {
			throw new IllegalArgumentException("b wasn't consistent");
		}
		final int m = A.length;
		final int n = A[0].length;
		final int degree = n-m;
		final int[] b = IntMat.mapVector(rowDescr,bIn); 
		final int[] bInterior = getAConeInteriorPt(b);  // TODO: confirm the (implied) lemma that cones agree on closures is true
		// Note: zeros in rhsGroup(b) are a problem, as parts of the wedge solution might guess different extensions
		// So need to find a new bp without any zeros in rhsGroup(bp) (and also still compatible with the original problem)
		final IntVec coneGroup = rhsGroup(bInterior);   // If we were really going to use this we would cache on cone-group, or on b's rhsGroup
		if((!zeroFree(coneGroup))||(!compatibleSigns(rhsGroup(b),coneGroup))) {
			throw new IllegalStateException("failed to find and interior point");
		}
		final int[] wedgeBase = placeWedgeBase(coneGroup); 
		final IntVec baseGroup = rhsGroup(wedgeBase);
		if(!compatibleSigns(coneGroup,baseGroup)) {
			throw new IllegalStateException("wedge base wasn't in cone");
		}
		for(int i=0;i<m;++i) {
			final int[] wi = Arrays.copyOf(wedgeBase,wedgeBase.length);
			wi[i] = wedgeBase[i] + degree;
			final IntVec baseGroupI = rhsGroup(wi);
			if(!compatibleSigns(coneGroup,baseGroupI)) {
				throw new IllegalStateException("wedge vertex wasn't in cone");
			}
		}
		// now in principle could count on every item in baseGroup wedge (baseGroup + a right orthant) to 
		// get the counting polynomial for the cone and then evaluate the polynomial at b (which is in the closure of the cone)
		// Lagrange interpolation would let us do this with integer-only arithmetic.
		// Not pursuing this as the entries seem to get large too fast to be useful (now that we have the even/odd counter).
		return wedgeBase;
	}
	
	public static void main(final String[] args) throws LPException {
		for(int n=1;n<=4;++n) {
			System.out.println("n:" + n + "\t" + new Date());
			final CountingProblem prob = new ContingencyTableProblem(n,n);
			final Cones cones = new Cones(prob.A);
			System.out.println("\tcheck conditions: " + cones.checkVecs.length);
			final int b[] = new int[2*n];
			Arrays.fill(b,10);
			System.out.println("\tinterpolation base: " + new IntVec(cones.buildConeWedge(b)));
		}
		System.out.println(new Date());
	}
}