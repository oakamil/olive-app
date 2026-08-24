from scipy.stats import binom
def test_cdf(N, M, C, r): return binom.cdf(N - (M - 2), N, 1.0 - C * (r**2))
print(f"N=80, M=6, C=15: {test_cdf(80, 6, 15, 0.01):.2e}")
print(f"N=30, M=6, C=15: {test_cdf(30, 6, 15, 0.01):.2e}")
print(f"N=20, M=6, C=15: {test_cdf(20, 6, 15, 0.01):.2e}")
