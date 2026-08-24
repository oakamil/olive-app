from scipy.stats import binom
def test_cdf(N, M, C, r): return binom.cdf(N - (M - 2), N, 1.0 - C * (r**2))
print(f"N=12, M=4: {test_cdf(12, 4, 15, 0.01):.2e}")
print(f"N=12, M=5: {test_cdf(12, 5, 15, 0.01):.2e}")
print(f"N=12, M=6: {test_cdf(12, 6, 15, 0.01):.2e}")
print(f"N=15, M=4: {test_cdf(15, 4, 15, 0.01):.2e}")
print(f"N=15, M=5: {test_cdf(15, 5, 15, 0.01):.2e}")
print(f"N=15, M=6: {test_cdf(15, 6, 15, 0.01):.2e}")
