/*
 * This C++ source file was generated by the Gradle 'init' task.
 */

#include "foo.h"
#include <cassert>

int main() {
    foo::Greeter greeter;
    assert(greeter.greeting().compare("Hello, World!") == 0);
    return 0;
}