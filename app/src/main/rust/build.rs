// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

fn main() {
    println!("cargo:rustc-link-lib=mediandk");
    println!("cargo:rustc-link-lib=camera2ndk");
    println!("cargo:rustc-link-lib=android");
}
