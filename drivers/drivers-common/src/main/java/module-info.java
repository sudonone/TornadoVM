module tornado.drivers.common {
    requires transitive jdk.internal.vm.compiler;
    requires transitive jdk.internal.vm.ci;
    requires transitive tornado.runtime;

    exports uk.ac.manchester.tornado.drivers.graal;
    exports uk.ac.manchester.tornado.drivers.common.graal.compiler;
}
