package io.izzel.arclight.common.mixin.core.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import io.izzel.arclight.common.mod.util.DistValidate;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v.block.CraftBlock;
import org.bukkit.craftbukkit.v.block.CraftBlockState;
import org.bukkit.craftbukkit.v.block.CraftBlockStates;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(VineBlock.class)
public abstract class VineBlockMixin extends BlockMixin {

    // @formatter:off
    @Shadow public static BooleanProperty getPropertyForFace(Direction side) { return null; }
    @Shadow protected abstract boolean canSpread(BlockGetter blockReader, BlockPos pos);
    @Shadow public static boolean isAcceptableNeighbour(BlockGetter blockReader, BlockPos worldIn, Direction neighborPos) { return false; }
    @Shadow @Final public static BooleanProperty UP;
    @Shadow protected abstract boolean canSupportAtFace(BlockGetter blockReader, BlockPos pos, Direction direction);
    @Shadow protected abstract boolean hasHorizontalConnection(BlockState state);
    @Shadow protected abstract BlockState copyRandomFaces(BlockState state, BlockState state2, RandomSource rand);
    // @formatter:on

    /**
     * @author IzzelAliz
     * @reason
     */
    @SuppressWarnings("ConstantConditions")
    @Overwrite
    public void randomTick(BlockState state, ServerLevel worldIn, BlockPos pos, RandomSource random) {
        if (!worldIn.getGameRules().getBoolean(GameRules.RULE_DO_VINES_SPREAD)) {
            return;
        }
        if (worldIn.random.nextInt(4) == 0 && worldIn.isAreaLoaded(pos, 4)) { // Forge: check area to prevent loading unloaded chunks
            Direction direction = Direction.getRandom(random);
            BlockPos blockpos = pos.above();
            if (direction.getAxis().isHorizontal() && !state.getValue(getPropertyForFace(direction))) {
                if (this.canSpread(worldIn, pos)) {
                    BlockPos blockpos4 = pos.relative(direction);
                    BlockState blockstate4 = worldIn.getBlockState(blockpos4);
                    if (blockstate4.isAir()) {
                        Direction direction3 = direction.getClockWise();
                        Direction direction4 = direction.getCounterClockWise();
                        boolean flag = state.getValue(getPropertyForFace(direction3));
                        boolean flag1 = state.getValue(getPropertyForFace(direction4));
                        BlockPos blockpos2 = blockpos4.relative(direction3);
                        BlockPos blockpos3 = blockpos4.relative(direction4);
                        if (flag && isAcceptableNeighbour(worldIn, blockpos2, direction3)) {
                            BlockState newState = this.defaultBlockState().setValue(getPropertyForFace(direction3), Boolean.TRUE);
                            if (this.arclight$blockSpread(worldIn, pos, blockpos4, newState, 2)) {
                                worldIn.setBlock(blockpos4, newState, 2);
                            }
                        } else if (flag1 && isAcceptableNeighbour(worldIn, blockpos3, direction4)) {
                            BlockState newState = this.defaultBlockState().setValue(getPropertyForFace(direction4), Boolean.TRUE);
                            if (this.arclight$blockSpread(worldIn, pos, blockpos4, newState, 2)) {
                                worldIn.setBlock(blockpos4, newState, 2);
                            }
                        } else {
                            Direction direction1 = direction.getOpposite();
                            if (flag && worldIn.isEmptyBlock(blockpos2) && isAcceptableNeighbour(worldIn, pos.relative(direction3), direction1)) {
                                BlockState newState = this.defaultBlockState().setValue(getPropertyForFace(direction1), Boolean.TRUE);
                                if (this.arclight$blockSpread(worldIn, pos, blockpos2, newState, 2)) {
                                    worldIn.setBlock(blockpos2, newState, 2);
                                }
                            } else if (flag1 && worldIn.isEmptyBlock(blockpos3) && isAcceptableNeighbour(worldIn, pos.relative(direction4), direction1)) {
                                BlockState newState = this.defaultBlockState().setValue(getPropertyForFace(direction1), Boolean.TRUE);
                                if (this.arclight$blockSpread(worldIn, pos, blockpos3, newState, 2)) {
                                    worldIn.setBlock(blockpos3, newState, 2);
                                }
                            } else if ((double) worldIn.random.nextFloat() < 0.05D && isAcceptableNeighbour(worldIn, blockpos4.above(), Direction.UP)) {
                                BlockState newState = this.defaultBlockState().setValue(UP, Boolean.TRUE);
                                if (this.arclight$blockSpread(worldIn, pos, blockpos4, newState, 2)) {
                                    worldIn.setBlock(blockpos4, newState, 2);
                                }
                            }
                        }
                    } else if (isAcceptableNeighbour(worldIn, blockpos4, direction)) {
                        BlockState newState = state.setValue(getPropertyForFace(direction), Boolean.TRUE);
                        if (this.arclight$blockGrow(worldIn, pos, newState, 2)) {
                            worldIn.setBlock(pos, newState, 2);
                        }
                    }

                }
            } else {
                if (direction == Direction.UP && pos.getY() < worldIn.getMaxBuildHeight() - 1) {
                    if (this.canSupportAtFace(worldIn, pos, direction)) {
                        BlockState newState = state.setValue(UP, Boolean.TRUE);
                        if (this.arclight$blockGrow(worldIn, pos, newState, 2)) {
                            worldIn.setBlock(pos, newState, 2);
                        }
                        return;
                    }

                    if (worldIn.isEmptyBlock(blockpos)) {
                        if (!this.canSpread(worldIn, pos)) {
                            return;
                        }

                        BlockState blockstate3 = state;

                        for (Direction direction2 : Direction.Plane.HORIZONTAL) {
                            if (random.nextBoolean() || !isAcceptableNeighbour(worldIn, blockpos.relative(direction2), Direction.UP)) {
                                blockstate3 = blockstate3.setValue(getPropertyForFace(direction2), Boolean.FALSE);
                            }
                        }

                        if (this.hasHorizontalConnection(blockstate3)) {
                            if (this.arclight$blockSpread(worldIn, pos, blockpos, blockstate3, 2)) {
                                worldIn.setBlock(blockpos, blockstate3, 2);
                            }
                        }

                        return;
                    }
                }

                if (pos.getY() > worldIn.getMinBuildHeight()) {
                    BlockPos blockpos1 = pos.below();
                    BlockState blockstate = worldIn.getBlockState(blockpos1);
                    boolean isAir = blockstate.isAir();
                    if (isAir || blockstate.is((Block) (Object) this)) {
                        BlockState blockstate1 = isAir ? this.defaultBlockState() : blockstate;
                        BlockState blockstate2 = this.copyRandomFaces(state, blockstate1, random);
                        if (blockstate1 != blockstate2 && this.hasHorizontalConnection(blockstate2)) {
                            if (this.arclight$blockSpread(worldIn, pos, blockpos1, blockstate2, 2)) {
                                worldIn.setBlock(blockpos1, blockstate2, 2);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean arclight$blockSpread(ServerLevel world, BlockPos source, BlockPos target, BlockState block, int flag) {
        // Suppress during worldgen
        if (!DistValidate.isValid(world)) {
            return true;
        }

        CraftBlockState state = CraftBlockStates.getBlockState(world, target, flag);
        state.setData(block);

        BlockSpreadEvent event = new BlockSpreadEvent(state.getBlock(), CraftBlock.at(world, source), state);
        Bukkit.getPluginManager().callEvent(event);

        return !event.isCancelled();
    }

    private boolean arclight$blockGrow(ServerLevel world, BlockPos pos, BlockState newData, int flag) {
        // Suppress during worldgen
        if (!DistValidate.isValid(world)) {
            return true;
        }

        org.bukkit.block.Block block = ((io.izzel.arclight.common.bridge.core.world.WorldBridge) world).bridge$getWorld().getBlockAt(pos.getX(), pos.getY(), pos.getZ());
        CraftBlockState state = (CraftBlockState) block.getState();
        state.setData(newData);

        BlockGrowEvent event = new BlockGrowEvent(block, state);
        Bukkit.getPluginManager().callEvent(event);

        return !event.isCancelled();
    }
}
