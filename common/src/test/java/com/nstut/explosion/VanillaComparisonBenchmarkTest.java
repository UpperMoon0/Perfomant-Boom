package com.nstut.explosion;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
class VanillaComparisonBenchmarkTest {
  private static volatile long sink;
  @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
  @Test void compareAgainstVanilla1201RayLoop(){
    bench("power-8 forest",view((x,y,z)->y>64?Blocks.AIR.defaultBlockState():(y==64?Blocks.GRASS_BLOCK.defaultBlockState():Blocks.DIRT.defaultBlockState())),new Vec3(0.5,64.5,0.5),8f);
    bench("power-10 forest",view((x,y,z)->y>64?Blocks.AIR.defaultBlockState():(y==64?Blocks.GRASS_BLOCK.defaultBlockState():Blocks.DIRT.defaultBlockState())),new Vec3(0.5,64.5,0.5),10f);
    bench("power-15 underground",view((x,y,z)->y<0?Blocks.DEEPSLATE.defaultBlockState():Blocks.STONE.defaultBlockState()),new Vec3(0.5,-9.5,0.5),15f);
    bench("power-60 stone",view((x,y,z)->Blocks.STONE.defaultBlockState()),new Vec3(0.5,0.5,0.5),60f);
  }
  private static void bench(String label,FastExplosionEngine.BlockView w,Vec3 c,float p){
    int warm=8,n=21; for(int i=0;i<warm;i++){vanilla(w,c,p,0xB00B135L+i);fast(w,c,p,0xB00B135L+i);}
    long[] v=new long[n],f=new long[n]; int vt=0,vn=0,fn=0; long samples=0;
    for(int i=0;i<n;i++){long seed=0xB00B135L+1000+i; if((i&1)==0){long s=System.nanoTime();VResult vr=vanilla(w,c,p,seed);v[i]=System.nanoTime()-s;s=System.nanoTime();FResult fr=fast(w,c,p,seed);f[i]=System.nanoTime()-s;vt=vr.total;vn=vr.nonAir;fn=fr.nonAir;samples=fr.samples;}else{long s=System.nanoTime();FResult fr=fast(w,c,p,seed);f[i]=System.nanoTime()-s;s=System.nanoTime();VResult vr=vanilla(w,c,p,seed);v[i]=System.nanoTime()-s;vt=vr.total;vn=vr.nonAir;fn=fr.nonAir;samples=fr.samples;}}
    Arrays.sort(v);Arrays.sort(f);double vm=v[n/2]/1e6,fm=f[n/2]/1e6;
    System.out.printf("VANILLA-COMPARE %s | vanilla %.3f ms [p25 %.3f,p75 %.3f] | fast %.3f ms [p25 %.3f,p75 %.3f] | speedup %.2fx | vanilla-set %d (nonair %d) | fast-set %d | samples %d%n",label,vm,v[n/4]/1e6,v[(n*3)/4]/1e6,fm,f[n/4]/1e6,f[(n*3)/4]/1e6,vm/fm,vt,vn,fn,samples);sink+=vt+vn+fn+samples;
  }
  record FResult(int nonAir,long samples){}
  static FResult fast(FastExplosionEngine.BlockView w,Vec3 c,float p,long seed){var x=FastExplosionEngine.create(w,c,p,RandomSource.create(seed));x.processUntil(Long.MAX_VALUE);return new FResult(x.blockCount(),x.sampleCount());}
  record VResult(int total,int nonAir){}
  static VResult vanilla(FastExplosionEngine.BlockView w,Vec3 c,float p,long seed){
    Set<BlockPos> r=new HashSet<>(); RandomSource random=RandomSource.create(seed);
    for(int rx=0;rx<16;rx++)for(int ry=0;ry<16;ry++)ray:for(int rz=0;rz<16;rz++){if(rx!=0&&rx!=15&&ry!=0&&ry!=15&&rz!=0&&rz!=15)continue;double dx=(float)rx/15f*2f-1f,dy=(float)ry/15f*2f-1f,dz=(float)rz/15f*2f-1f;double len=Math.sqrt(dx*dx+dy*dy+dz*dz);dx/=len;dy/=len;dz/=len;double x=c.x,y=c.y,z=c.z;for(float strength=p*(0.7f+random.nextFloat()*0.6f);strength>0f;strength-=0.22500001f){int bx=Mth.floor(x),by=Mth.floor(y),bz=Mth.floor(z);if(!w.isInWorldBounds(bx,by,bz))continue ray;BlockPos pos=BlockPos.containing(x,y,z);BlockState state=w.getBlockState(bx,by,bz);FluidState fluid=state.getFluidState();Optional<Float> resistance=(state.isAir()&&fluid.isEmpty())?Optional.empty():Optional.of(Math.max(state.getBlock().getExplosionResistance(),fluid.getExplosionResistance()));if(resistance.isPresent())strength-=(resistance.get()+0.3f)*0.3f;if(strength>0f)r.add(pos);x+=dx*(double)0.3f;y+=dy*(double)0.3f;z+=dz*(double)0.3f;}}
    int non=0;for(BlockPos pos:r)if(!w.getBlockState(pos.getX(),pos.getY(),pos.getZ()).isAir())non++;return new VResult(r.size(),non);
  }
  interface StateAt{BlockState get(int x,int y,int z);}
  static FastExplosionEngine.BlockView view(StateAt s){return new FastExplosionEngine.BlockView(){public boolean isInWorldBounds(int x,int y,int z){return y>=-64&&y<320&&x>=-30000000&&z>=-30000000&&x<30000000&&z<30000000;}public BlockState getBlockState(int x,int y,int z){return s.get(x,y,z);}};}
}
