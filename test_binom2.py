from scipy.stats import binom
def test_cdf(N, M, C, r): return binom.cdf(N - (M - 2), N, 1.0 - C * (r**2))
print(f"N=25, M=4, C=20: {test_cdf(25, 4, 20, 0.01):.2e}")
print(f"N=25, M=5, C=20: {test_cdf(25, 5, 20, 0.01):.2e}")
print(f"N=25, M=6, C=20: {test_cdf(25, 6, 20, 0.01):.2e}")
print(f"N=25, M=7, C=20: {test_cdf(25, 7, 20, 0.01):.2e}")
print(f"N=25, M=8, C=20: {test_cdf(25, 8, 20, 0.01):.2e}")
