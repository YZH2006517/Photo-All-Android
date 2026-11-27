package org.photoall.film.retouch;
/** Learned 33-cube LUTs, [basis][channel][blue][green][red]. */
final class Lut {
 private static final int CUBE=33*33*33;
 private final float[] data;
 Lut(float[] data){
  if(data.length!=3*3*CUBE)throw new IllegalArgumentException("LUT 数据长度无效");
  for(float value:data)if(!Float.isFinite(value))throw new IllegalArgumentException("LUT 数据无效");
  this.data=data;
 }
 static void validate(float[] weights){
  if(weights.length!=3)throw new IllegalArgumentException("模型权重维度无效");
  for(float weight:weights)if(!Float.isFinite(weight))throw new IllegalArgumentException("模型返回非有限权重");
 }
 int pixel(int argb,float[] weights,float strength){
  float r=((argb>>16)&255)/255f,g=((argb>>8)&255)/255f,b=(argb&255)/255f;
  float x=r/(1.000001f/32),y=g/(1.000001f/32),z=b/(1.000001f/32);
  int ri=Math.min(31,(int)x),gi=Math.min(31,(int)y),bi=Math.min(31,(int)z);
  float dx=x-ri,dy=y-gi,dz=z-bi;
  int result=argb&0xff000000;
  for(int channel=0;channel<3;channel++){
   float value=0;
   for(int basis=0;basis<3;basis++){
    int offset=(basis*3+channel)*CUBE+bi*1089+gi*33+ri;
    float low=(data[offset]*(1-dx)+data[offset+1]*dx)*(1-dy)+(data[offset+33]*(1-dx)+data[offset+34]*dx)*dy;
    float high=(data[offset+1089]*(1-dx)+data[offset+1090]*dx)*(1-dy)+(data[offset+1122]*(1-dx)+data[offset+1123]*dx)*dy;
    value+=weights[basis]*(low*(1-dz)+high*dz);
   }
   float original=channel==0?r:channel==1?g:b;
   value=original*(1-strength)+Math.max(0,Math.min(1,value))*strength;
   result|=Math.round(Math.max(0,Math.min(1,value))*255)<<(16-channel*8);
  }
  return result;
 }
}
