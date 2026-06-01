package org.complexityanalyzer.harvest;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.attachment.AttachmentHolder;

import java.lang.reflect.Method;
import java.util.Objects;

public final class ItemStackIdentity {

    private ItemStackIdentity() {
    }

    public static boolean sameItemData(ItemStack a, ItemStack b) {
        return sameItemData(a, b, null);
    }

    public static boolean sameItemData(ItemStack a, ItemStack b, HolderLookup.Provider provider) {
        if (a == b) return true;
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        if (!ItemStack.isSameItemSameComponents(a, b)) return false;
        return Objects.equals(serializedAttachments(a, provider), serializedAttachments(b, provider));
    }

    public static boolean sameItemDataAndCount(ItemStack a, ItemStack b) {
        return sameItemDataAndCount(a, b, null);
    }

    public static boolean sameItemDataAndCount(ItemStack a, ItemStack b, HolderLookup.Provider provider) {
        return sameItemData(a, b, provider) && a.getCount() == b.getCount();
    }

    public static boolean hasStackData(ItemStack stack) {
        return hasStackData(stack, null);
    }

    public static boolean hasStackData(ItemStack stack, HolderLookup.Provider provider) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.isComponentsPatchEmpty()) return true;
        var attachments = serializedAttachments(stack, provider);
        return attachments != null && !attachments.isEmpty();
    }

    public static int hashItemDataAndCount(ItemStack stack) {
        return hashItemDataAndCount(stack, null);
    }

    public static int hashItemData(ItemStack stack) {
        return hashItemData(stack, null);
    }

    public static int hashItemData(ItemStack stack, HolderLookup.Provider provider) {
        if (stack == null || stack.isEmpty()) return 0;
        int result = ItemStack.hashItemAndComponents(stack);
        var attachments = serializedAttachments(stack, provider);
        return 31 * result + (attachments != null ? attachments.hashCode() : 0);
    }

    public static int hashItemDataAndCount(ItemStack stack, HolderLookup.Provider provider) {
        if (stack == null || stack.isEmpty()) return 0;
        int result = hashItemData(stack, provider);
        result = 31 * result + stack.getCount();
        return result;
    }

    public static String dataKey(ItemStack stack, HolderLookup.Provider provider) {
        if (stack == null || stack.isEmpty()) return "";
        StringBuilder key = new StringBuilder();
        key.append("item=").append(stack.getItem());
        key.append(";components=").append(stack.getComponentsPatch());
        var attachments = serializedAttachments(stack, provider);
        if (attachments != null && !attachments.isEmpty()) key.append(";attachments=").append(attachments);
        if (provider != null) {
            try {
                Tag saved = stack.saveOptional(provider);
                key.append(";saved=").append(saved);
            } catch (Throwable ignored) {
            }
        }
        return key.toString();
    }

    private static CompoundTag serializedAttachments(ItemStack stack, HolderLookup.Provider provider) {
        if (provider == null) return null;
        return serializedAttachments((Object) stack, provider);
    }

    private static CompoundTag serializedAttachments(Object holder, HolderLookup.Provider provider) {
        if (holder instanceof AttachmentHolder attachmentHolder) {
            try {
                return attachmentHolder.serializeAttachments(provider);
            } catch (Throwable ignored) {
                return null;
            }
        }

        try {
            Method method = holder.getClass().getMethod("serializeAttachments", HolderLookup.Provider.class);
            if (!CompoundTag.class.isAssignableFrom(method.getReturnType())) return null;
            return (CompoundTag) method.invoke(holder, provider);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
