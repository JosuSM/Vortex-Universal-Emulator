fn main() {
    cc::Build::new()
        .file("src/log_shim.c")
        .compile("log_shim");
    println!("cargo:rustc-link-lib=log");
}
