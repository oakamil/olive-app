fn main() {
    println!("cargo:rustc-link-lib=mediandk");
    println!("cargo:rustc-link-lib=camera2ndk");
    println!("cargo:rustc-link-lib=android");
}
