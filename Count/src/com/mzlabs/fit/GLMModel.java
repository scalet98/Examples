package com.mzlabs.fit;

import java.util.Arrays;

import com.winvector.linalg.colt.ColtMatrix;

public class GLMModel implements VectorFnWithJacobian {
	public final BalanceJacobianCalc balanceJacobianCalc;

	public GLMModel(final BalanceJacobianCalc balanceJacobianCalc) {
		this.balanceJacobianCalc = balanceJacobianCalc;
	}
	
	@Override
	public String toString() {
		return "GLM(" + balanceJacobianCalc.toString() + ")";
	}
	
	@Override
	public void balanceAndJacobian(final Iterable<Obs> obs, final double[] beta,
			final double[] balance, final ColtMatrix jacobian) {
		final int dim = beta.length;
		Arrays.fill(balance,0.0);
		for(int i=0;i<dim;++i) {
			for(int j=0;j<dim;++j) {
				jacobian.set(i,j,0.0);
			}
		}
		for(final Obs obsi: obs) {
			final BalanceJacobianCoef ghc = balanceJacobianCalc.calc(obsi,beta);
			for(int i=0;i<dim;++i) {
				final double xi = obsi.x[i];
				balance[i] += ghc.balanceCoef*xi;
				for(int j=0;j<dim;++j) {
					final double xj = obsi.x[j];
					final double hij = jacobian.get(i,j);
					jacobian.set(i,j,hij+xi*xj*ghc.jacobianCoef);
				}
			}
		}
	}

	@Override
	public double evalEst(double[] beta, double[] x) {
		return balanceJacobianCalc.evalEst(beta,x);
	}
	
	@Override
	public double heuristicLink(final double y) {
		return balanceJacobianCalc.heuristicLink(y);
	}

	@Override
	public int dim(final Obs obs) {
		return obs.x.length;
	}
}
