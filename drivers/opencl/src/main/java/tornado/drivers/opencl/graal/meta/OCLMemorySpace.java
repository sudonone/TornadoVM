package tornado.drivers.opencl.graal.meta;

import com.oracle.graal.compiler.common.LIRKind;
import jdk.vm.ci.meta.Value;
import tornado.drivers.opencl.graal.OCLArchitecture;
import tornado.drivers.opencl.graal.asm.OCLAssemblerConstants;

import static tornado.common.exceptions.TornadoInternalError.shouldNotReachHere;

public class OCLMemorySpace extends Value {
    // @formatter:off

    public static final OCLMemorySpace GLOBAL = new OCLMemorySpace(OCLAssemblerConstants.GLOBAL_MEM_MODIFIER);
//        public static final OCLMemorySpace SHARED = new OCLMemorySpace(OCLAssemblerConstants.SHARED_MEM_MODIFIER);
    public static final OCLMemorySpace LOCAL = new OCLMemorySpace(OCLAssemblerConstants.LOCAL_MEM_MODIFIER);
    public static final OCLMemorySpace PRIVATE = new OCLMemorySpace(OCLAssemblerConstants.PRIVATE_MEM_MODIFIER);
    public static final OCLMemorySpace CONSTANT = new OCLMemorySpace(OCLAssemblerConstants.CONSTANT_MEM_MODIFIER);
    public static final OCLMemorySpace HEAP = new OCLMemorySpace("heap");
    // @formatter:on

    private final String name;

    protected OCLMemorySpace(String name) {
        super(LIRKind.Illegal);
        this.name = name;
    }

    public OCLArchitecture.OCLMemoryBase getBase() {

        if (this == GLOBAL || this == HEAP) {
            return OCLArchitecture.hp;
        } else if (this == LOCAL) {
            return OCLArchitecture.lp;
        } else if (this == CONSTANT) {
            return OCLArchitecture.cp;
        } else if (this == PRIVATE) {
            return OCLArchitecture.pp;
        }

        shouldNotReachHere();
        return null;
    }

    public String name() {
        return name;
    }
}