from scipy.stats import binom
def test_cdf(N, M, C, r): return binom.cdf(N - (M - 2), N, 1.0 - C * (r**2))
for m in range(4, 9):
    print(f"N=15, M={m}, C=20: {test_cdf(15, m, 20, 0.01):.2e}")
print("\n")
for m in range(4, 9):
    print(f"N=20, M={m}, C=20: {test_cdf(20, m, 20, 0.01):.2e}")
