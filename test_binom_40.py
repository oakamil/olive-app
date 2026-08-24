from scipy.stats import binom
def test_cdf(N, M, C, r): return binom.cdf(N - (M - 2), N, 1.0 - C * (r**2))
print(f"N=12, M=6, C=40: {test_cdf(12, 6, 40, 0.01):.2e}")
print(f"N=12, M=7, C=40: {test_cdf(12, 7, 40, 0.01):.2e}")
