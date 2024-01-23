/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.client.render.model;


import appeng.client.render.VertexFormats;
import appeng.core.AELog;

import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.gson.*;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EnumFaceDirection;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.JsonUtils;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.EnumHelperClient;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.client.model.ICustomModelLoader;
import net.minecraftforge.client.model.IModel;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.client.model.ModelLoaderRegistry;
import net.minecraftforge.client.model.animation.ModelBlockAnimation;
import net.minecraftforge.client.model.pipeline.UnpackedBakedQuad;
import net.minecraftforge.client.model.pipeline.VertexLighterFlat;
import net.minecraftforge.common.model.IModelState;
import net.minecraftforge.common.model.ITransformation;
import net.minecraftforge.common.model.Models;
import net.minecraftforge.common.model.TRSRTransformation;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.util.vector.Vector3f;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;


public enum UVLModelLoader implements ICustomModelLoader {
    INSTANCE;

    private static final Gson gson = new Gson();

    private static final Constructor<? extends IModel> vanillaModelWrapper;
    private static final Field faceBakery;
    private static final Object vanillaLoader;
    private static final MethodHandle loaderGetter;

    static {
        try {
            faceBakery = ReflectionHelper.findField(ModelBakery.class, "faceBakery", "field_177607_l");

            Class clas = Class.forName(ModelLoader.class.getName() + "$VanillaModelWrapper");
            vanillaModelWrapper = clas.getDeclaredConstructor(ModelLoader.class, ResourceLocation.class, ModelBlock.class, boolean.class,
                    ModelBlockAnimation.class);
            vanillaModelWrapper.setAccessible(true);

            Class<?> vanillaLoaderClass = Class.forName(ModelLoader.class.getName() + "$VanillaLoader");
            Field instanceField = vanillaLoaderClass.getField("INSTANCE");
            // Static field
            vanillaLoader = instanceField.get(null);
            Field loaderField = vanillaLoaderClass.getDeclaredField("loader");
            loaderField.setAccessible(true);
            loaderGetter = MethodHandles.lookup().unreflectGetter(loaderField);
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    private static Object deserializer(Class clas) {
        try {
            clas = Class.forName(clas.getName() + "$Deserializer");
            Constructor constr = clas.getDeclaredConstructor();
            constr.setAccessible(true);
            return constr.newInstance();
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    private static <M extends IModel> M vanillaModelWrapper(ModelLoader loader, ResourceLocation location, ModelBlock model, boolean uvlock, ModelBlockAnimation animation) {
        try {
            return (M) vanillaModelWrapper.newInstance(loader, location, model, uvlock, animation);
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    private static void setFaceBakery(ModelBakery modelBakery, FaceBakery faceBakery) {
        try {
            EnumHelperClient.setFailsafeFieldValue(UVLModelLoader.faceBakery, modelBakery, faceBakery);
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    private IResourceManager resourceManager;

    public ModelLoader getLoader() {
        try {
            return (ModelLoader) loaderGetter.invoke(vanillaLoader);
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }

    @Override
    public void onResourceManagerReload(IResourceManager resourceManager) {
        this.resourceManager = resourceManager;
    }

    @Override
    public boolean accepts(ResourceLocation modelLocation) {
        String modelPath = modelLocation.getPath();
        if (modelLocation.getPath().startsWith("models/")) {
            modelPath = modelPath.substring("models/".length());
        }

        try (InputStreamReader io = new InputStreamReader(Minecraft.getMinecraft()
                .getResourceManager()
                .getResource(new ResourceLocation(modelLocation.getNamespace(), "models/" + modelPath + ".json"))
                .getInputStream())) {
            return gson.fromJson(io, UVLMarker.class).ae2_uvl_marker;
        } catch (Exception e) {
            // Catch-all in case of any JSON parser issues.
        }

        return false;
    }

    @Override
    public IModel loadModel(ResourceLocation modelLocation) throws Exception {
        return new UVLModel(modelLocation);
    }

    public class UVLModel implements IModel {
        final Gson UVLSERIALIZER = (new GsonBuilder()).registerTypeAdapter(ModelBlock.class, deserializer(ModelBlock.class))
                .registerTypeAdapter(BlockPart.class, deserializer(BlockPart.class))
                .registerTypeAdapter(BlockPartFace.class, new BlockPartFaceOverrideSerializer())
                .registerTypeAdapter(BlockFaceUV.class, deserializer(BlockFaceUV.class))
                .registerTypeAdapter(ItemTransformVec3f.class, deserializer(ItemTransformVec3f.class))
                .registerTypeAdapter(ItemCameraTransforms.class, deserializer(ItemCameraTransforms.class))
                .registerTypeAdapter(ItemOverride.class, deserializer(ItemOverride.class))
                .create();

        private final Map<BlockPartFace, Pair<Float, Float>> uvlightmap = new HashMap<>();

        private final ResourceLocation location;
        private final ModelBlock model;
        private static final boolean uvLocked = false; //uvlock is always false!
        private final ModelBlockAnimation animation;
        private static final FaceBakery faceBakery = new FaceBakery();

        public UVLModel(ResourceLocation modelLocation) {
        	this.location = modelLocation;
        	String modelPath = modelLocation.getPath();
            if (modelLocation.getPath().startsWith("models/")) {
                modelPath = modelPath.substring("models/".length());
            }
            ResourceLocation armatureLocation = new ResourceLocation(modelLocation.getNamespace(), "armatures/" + modelPath + ".json");
            this.animation = ModelBlockAnimation.loadVanillaAnimation(UVLModelLoader.this.resourceManager, armatureLocation);
            {
                Reader reader = null;
                IResource iresource = null;
                ModelBlock lvt_5_1_ = null;

                try {
                    String s = modelLocation.getPath();

                    iresource = Minecraft.getMinecraft()
                            .getResourceManager()
                            .getResource(
                                    new ResourceLocation(modelLocation.getNamespace(), "models/" + modelPath + ".json"));
                    reader = new InputStreamReader(iresource.getInputStream(), Charsets.UTF_8);

                    lvt_5_1_ = JsonUtils.gsonDeserialize(this.UVLSERIALIZER, reader, ModelBlock.class, false);
                    lvt_5_1_.name = modelLocation.toString();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    IOUtils.closeQuietly(reader);
                    IOUtils.closeQuietly(iresource);
                }

                this.model = lvt_5_1_;
            }

        }

        @Override
        public Collection<ResourceLocation> getDependencies() {
        	Set<ResourceLocation> set = Sets.newHashSet();
            for(ResourceLocation dep : model.getOverrideLocations())
            {
                if(!location.equals(dep))
                {
                    set.add(dep);
                }
            }
            if(model.getParentLocation() != null && !model.getParentLocation().getPath().startsWith("builtin/"))
            {
                set.add(model.getParentLocation());
            }
            return ImmutableSet.copyOf(set);
        }

        @Override
        public Collection<ResourceLocation> getTextures() {
            // setting parent here to make textures resolve properly
            ResourceLocation parentLocation = model.getParentLocation();
            if(parentLocation != null && model.parent == null)
            {
                    model.parent = ModelLoaderRegistry.getModelOrLogError(parentLocation, "Could not load vanilla model parent '" + parentLocation + "' for '" + model + "'")
                            .asVanillaModel().orElseThrow(() -> new IllegalStateException("vanilla model '" + model + "' can't have non-vanilla parent"));
            }
                ImmutableSet.Builder<ResourceLocation> builder = ImmutableSet.builder();

                for(String s : model.textures.values())
                {
                    if(!s.startsWith("#"))
                    {
                        builder.add(new ResourceLocation(s));
                    }
                }
                return builder.build();
            
        }

        @Override
        public IBakedModel bake(IModelState state, VertexFormat format, Function<ResourceLocation, TextureAtlasSprite> bakedTextureGetter) {
        	final TRSRTransformation baseState = state.apply(Optional.empty()).orElse(TRSRTransformation.identity());
        	TextureAtlasSprite particle = bakedTextureGetter.apply(new ResourceLocation(model.resolveTextureName("particle")));
                List<BakedQuad> generalCutout = new ArrayList<>();
                List<BakedQuad> generalBloom = new ArrayList<>();
                Map<EnumFacing, List<BakedQuad>> facingCutout = new HashMap<>();
                Map<EnumFacing, List<BakedQuad>> facingBloom = new HashMap<>();
                for (EnumFacing facing : EnumFacing.values()) {
                    facingCutout.put(facing, new ArrayList<>());
                    facingBloom.put(facing, new ArrayList<>());
                }
            
            
                for(int i = 0; i < model.getElements().size(); i++)
                    {
                        if(state.apply(Optional.of(Models.getHiddenModelPart(ImmutableList.of(Integer.toString(i))))).isPresent())
                            {
                                continue;
                            }
                        BlockPart part = model.getElements().get(i);
                        TRSRTransformation transformation = baseState;
            
                        for(Map.Entry<EnumFacing, BlockPartFace> e : part.mapFaces.entrySet())
                            {
                                TextureAtlasSprite textureatlassprite1 = bakedTextureGetter.apply(new ResourceLocation(model.resolveTextureName(e.getValue().texture)));

                                BakedQuad quad;
                                EnumFacing facing = null;
                                if (e.getValue().cullFace == null || !TRSRTransformation.isInteger(transformation.getMatrix()))
                                    {
                                        quad = makeBakedQuad(part, e.getValue(), textureatlassprite1, e.getKey(), transformation, uvLocked, format);
                                    }
                                else
                                    {
                                        facing = baseState.rotate(e.getValue().cullFace);
                                        quad = makeBakedQuad(part, e.getValue(), textureatlassprite1, e.getKey(), transformation, uvLocked, format);
                                    }
                                if (this.uvlightmap.get(e.getValue()) != null) {
                                    //TODO make brightness values actually matter
				    //int[] data = new int[quad.getVertexData().length + 1];
				    //System.arraycopy(quad.getVertexData(), 0, data, 0, quad.getVertexData().length + 1);
                                    //quad = new BakedQuad(data, quad.getTintIndex(), quad.getFace(), quad.getSprite(), quad.shouldApplyDiffuseLighting(), new VertexFormat(quad.getFormat()).addElement(DefaultVertexFormats.TEX_2S));
                                    if (facing != null) {
                                        facingBloom.get(facing).add(quad);
                                    } else {
                                        generalBloom.add(quad);
                                    }
                	
                                } else {
                                    if (facing != null) {
                                        facingCutout.get(facing).add(quad);
                                    } else {
                                        generalCutout.add(quad);               	}
                                }
                            }
                    }
                return new UVLBakedModel(generalCutout, facingCutout, generalBloom, facingBloom, particle);
        }
        
        private BakedQuad makeBakedQuad(BlockPart part, BlockPartFace face, TextureAtlasSprite sprite, EnumFacing facing, net.minecraftforge.common.model.ITransformation transform, boolean uvLocked, VertexFormat format)
        {
        	
        	Pair<Float, Float> light = uvlightmap.get(face);
        	boolean hasLightmap = false;
        	if (light != null) {
        		hasLightmap = (light.getLeft() > 0 || light.getRight() > 0);// && !FMLClientHandler.instance().hasOptifine();
        	}
            /*if (hasLightmap) { 
                if (format == DefaultVertexFormats.ITEM) { // ITEM is convertable to BLOCK (replace normal+padding with lmap)
                    format = DefaultVertexFormats.BLOCK;
                } else if (!format.getElements().contains(DefaultVertexFormats.TEX_2S)) { // Otherwise, this format is unknown, add TEX_2S if it does not exist
                    format = new VertexFormat(format).addElement(DefaultVertexFormats.TEX_2S);
                }
            }*/
            
        	format = new VertexFormat();
        	format.addElement(DefaultVertexFormats.POSITION_3F);
        	format.addElement(DefaultVertexFormats.TEX_2F);
        	
        	if (hasLightmap) {
        		format.addElement(DefaultVertexFormats.TEX_2S);
            }
            
            
            
            UnpackedBakedQuad.Builder builder = new UnpackedBakedQuad.Builder(format);
            builder.setQuadOrientation(facing);
            builder.setQuadTint(face.tintIndex);
            builder.setApplyDiffuseLighting(part.shade);
            builder.setTexture(sprite);
            
            float[] vertices = getPositionsDiv16(part.positionFrom, part.positionTo);
            //float[] vertices = {0, 0, 0, 1, 1, 1};

            for (int v = 0; v < 4; v++) {
                for (int i = 0; i < format.getElementCount(); i++) {
                    VertexFormatElement ele = format.getElement(i);
                    switch (ele.getUsage()) {
                    case UV:
                        if (ele.getIndex() == 1) {
                            //Stuff for fullbright, no clue which side is sky or block light
                        	if (hasLightmap)
                            builder.put(i, 0xFFFF, 0xFFFF);
                        } else if (ele.getIndex() == 0) {
                            BlockFaceUV faceUV = face.blockFaceUV;
                            builder.put(i, sprite.getInterpolatedU((double)faceUV.getVertexU(v) * .999 + faceUV.getVertexU((v + 2) % 4) * .001), sprite.getInterpolatedV((double)faceUV.getVertexV(v) * .999 + faceUV.getVertexV((v + 2) % 4) * .001));
                        }
                        break;
                    case POSITION:
                    	EnumFaceDirection.VertexInformation enumfacedirection$vertexinformation = EnumFaceDirection.getFacing(facing).getVertexInformation(v);
                        Vector3f p = new Vector3f(vertices[enumfacedirection$vertexinformation.xIndex], vertices[enumfacedirection$vertexinformation.yIndex], vertices[enumfacedirection$vertexinformation.zIndex]);
                        builder.put(i, p.x, p.y, p.z);
                        break;
                    case COLOR:
                        //builder.put(i, 255, 255, 255, 255); //Pretty things
                        break;
                    default:
                        //builder.put(i, this.builder.data.get(ele.getUsage()).get(v));
                    }
                }
            }

            AELog.info("Baking a quad");
            return builder.build();
        	//return faceBakery.makeBakedQuad(blockPartt.positionFrom, blockPartt.positionTo, blockPartFaceIn, sprite, face, transform, blockPartt.partRotation, uvLocked, blockPartt.shade);
        }
        
        private static float[] getPositionsDiv16(Vector3f pos1, Vector3f pos2)
        {
            float[] afloat = new float[EnumFacing.values().length];
            afloat[EnumFaceDirection.Constants.WEST_INDEX] = pos1.x / 16.0F;
            afloat[EnumFaceDirection.Constants.DOWN_INDEX] = pos1.y / 16.0F;
            afloat[EnumFaceDirection.Constants.NORTH_INDEX] = pos1.z / 16.0F;
            afloat[EnumFaceDirection.Constants.EAST_INDEX] = pos2.x / 16.0F;
            afloat[EnumFaceDirection.Constants.UP_INDEX] = pos2.y / 16.0F;
            afloat[EnumFaceDirection.Constants.SOUTH_INDEX] = pos2.z / 16.0F;
            return afloat;
        }

        @Override
        public IModelState getDefaultState() {
	    return TRSRTransformation.identity();
        }
        
        public static class UVLBakedModel implements IBakedModel {

            private final List<BakedQuad> generalCutout;
            private final List<BakedQuad> generalBloom;
            private final Map<EnumFacing, List<BakedQuad>> facingCutout;
            private final Map<EnumFacing, List<BakedQuad>> facingBloom;
            private final TextureAtlasSprite particle;
        	
            public UVLBakedModel(List<BakedQuad> generalCutout, Map<EnumFacing, List<BakedQuad>> facingCutout, List<BakedQuad> generalBloom, Map<EnumFacing, List<BakedQuad>> facingBloom, TextureAtlasSprite particle) {
        		this.generalCutout = generalCutout;
        		this.generalBloom = generalBloom;
        		this.facingCutout = facingCutout;
        		this.facingBloom = facingBloom;
                        this.particle = particle;
        		}
			@Override
			public List<BakedQuad> getQuads(IBlockState state, EnumFacing side, long rand) {
				BlockRenderLayer layer = MinecraftForgeClient.getRenderLayer();
				if (layer != null) {
				if (layer.ordinal() == 4) {
					if (side != null) {
						return this.facingBloom.get(side);
						} else {
							return this.generalBloom;
						}
				} else {
					if (side != null) {
						return this.facingCutout.get(side);
				} else {
					return this.generalCutout;
				}
				}
				} else {
				    if (side != null) {
					return this.facingCutout.get(side);
				    } else {
					return this.generalCutout;
				    }
				}
			
			}

			@Override
			public boolean isAmbientOcclusion() {
				// TODO Auto-generated method stub
				return false;
			}

			@Override
			public boolean isGui3d() {
				// TODO Auto-generated method stub
				return false;
			}

			@Override
			public boolean isBuiltInRenderer() {
				// TODO Auto-generated method stub
				return false;
			}

			@Override
			public TextureAtlasSprite getParticleTexture() {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public ItemOverrideList getOverrides() {
				// TODO Auto-generated method stub
				return null;
			}
        	
        }

        public class BlockPartFaceOverrideSerializer implements JsonDeserializer<BlockPartFace> {
            @Override
            public BlockPartFace deserialize(JsonElement p_deserialize_1_, Type p_deserialize_2_, JsonDeserializationContext p_deserialize_3_) throws JsonParseException {
                JsonObject jsonobject = p_deserialize_1_.getAsJsonObject();
                EnumFacing enumfacing = this.parseCullFace(jsonobject);
                int i = this.parseTintIndex(jsonobject);
                String s = this.parseTexture(jsonobject);
                BlockFaceUV blockfaceuv = p_deserialize_3_.deserialize(jsonobject, BlockFaceUV.class);
                BlockPartFace blockFace = new BlockPartFace(enumfacing, i, s, blockfaceuv);
                UVLModel.this.uvlightmap.put(blockFace, this.parseUVL(jsonobject));
                return blockFace;
            }

            protected int parseTintIndex(JsonObject object) {
                return JsonUtils.getInt(object, "tintindex", -1);
            }

            private String parseTexture(JsonObject object) {
                return JsonUtils.getString(object, "texture");
            }

            @Nullable
            private EnumFacing parseCullFace(JsonObject object) {
                String s = JsonUtils.getString(object, "cullface", "");
                return EnumFacing.byName(s);
            }

            protected Pair<Float, Float> parseUVL(JsonObject object) {
                if (!object.has("uvlightmap")) {
                    return null;
                }
                object = object.get("uvlightmap").getAsJsonObject();
                return new ImmutablePair<>(JsonUtils.getFloat(object, "sky", 0), JsonUtils.getFloat(object, "block", 0));
            }
            
            
        }

        public class FaceBakeryOverride extends FaceBakery {

            @Override
            public BakedQuad makeBakedQuad(Vector3f posFrom, Vector3f posTo, BlockPartFace face, TextureAtlasSprite sprite, EnumFacing facing, ITransformation modelRotationIn, BlockPartRotation partRotation, boolean uvLocked, boolean shade) {
                BakedQuad quad = super.makeBakedQuad(posFrom, posTo, face, sprite, facing, modelRotationIn, partRotation, uvLocked, shade);

                Pair<Float, Float> brightness = UVLModel.this.uvlightmap.get(face);
                BlockRenderLayer layer = MinecraftForgeClient.getRenderLayer();
                if (brightness != null && layer.ordinal() == 4) {
                    VertexFormat newFormat = VertexFormats.getFormatWithLightMap(quad.getFormat());
                    UnpackedBakedQuad.Builder builder = new UnpackedBakedQuad.Builder(newFormat);
                    /*VertexLighterFlat trans = new VertexLighterFlat(Minecraft.getMinecraft().getBlockColors()) {

                        @Override
                        protected void updateLightmap(float[] normal, float[] lightmap, float x, float y, float z) {
                            lightmap[0] = brightness.getRight();
                            lightmap[1] = brightness.getLeft();
                        }

                        @Override
                        public void setQuadTint(int tint) {
                            // Tint requires a block state which we don't have at this point
                        }
                    };
                    trans.setParent(builder);
                    quad.pipe(trans);*/
                    builder.setQuadTint(quad.getTintIndex());
                    builder.setQuadOrientation(quad.getFace());
                    builder.setTexture(quad.getSprite());
                    builder.setApplyDiffuseLighting(false);
                    return builder.build();
                } else {
                    return quad;
                }
            }

        }

    }

    class UVLMarker {
        boolean ae2_uvl_marker = false;
    }

}
