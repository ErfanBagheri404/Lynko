import test from 'node:test';
import assert from 'node:assert/strict';
const {mapPointer}=await import('./input-mapping.mjs').catch(()=>({}));
test('Fit maps the painted phone edges, ignoring letterbox',()=>{
 assert.equal(typeof mapPointer,'function');
 const rect={left:100,top:20,width:1000,height:600};
 assert.deepEqual(mapPointer(rect,720,1600,false,1,465,20),{x:0,y:0,inside:true});
 assert.deepEqual(mapPointer(rect,720,1600,false,1,735,620),{x:1,y:1,inside:true});
 assert.equal(mapPointer(rect,720,1600,false,1,101,300).inside,false);
});
test('Fill and zoom each use exactly one scaling factor',()=>{
 assert.equal(typeof mapPointer,'function');
 const rect={left:0,top:0,width:1000,height:600};
 assert.equal(mapPointer(rect,720,1600,true,1,0,300).x,0);
 assert.equal(mapPointer(rect,720,1600,true,2,0,300).x,.25);
 assert.equal(mapPointer(rect,720,1600,false,2,230,300).x,0);
});
