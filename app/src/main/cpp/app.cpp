/*
 * This C++ source file was generated by the Gradle 'init' task.
 */

#include <iostream>
#include <stdlib.h>
#include "app.h"

std::string sample_cpp_unit_test_uses_release_variant::Greeter::greeting() {
    return std::string("Hello, World!");
}

int main () {
    sample_cpp_unit_test_uses_release_variant::Greeter greeter;
    std::cout << greeter.greeting() << std::endl;
    return 0;
}
