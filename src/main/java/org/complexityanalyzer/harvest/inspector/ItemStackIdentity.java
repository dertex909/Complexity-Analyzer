/*
 * Complexity Analyzer
 * Copyright (C) 2025-2026 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.harvest.inspector;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.attachment.AttachmentHolder;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class ItemStackIdentity {

    private static final Method NO_METHOD;
    private static final ConcurrentHashMap<Class<?>, Method> ATTACHMENT_METHOD_CACHE = new ConcurrentHashMap<>();
    private static final String METHOD_SERIALIZE_ATTACHMENTS = MethodRefUtils.getMethodName1(AttachmentHolder.class, AttachmentHolder::serializeAttachments);

    static {
        Method m;
        try {
            m = ItemStackIdentity.class.getDeclaredMethod("noMethodSentinel");
        } catch (NoSuchMethodException e) {
            m = null;
        }
        NO_METHOD = m;
    }

    private ItemStackIdentity() {
    }

    private static void noMethodSentinel() {
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
        var key = new StringBuilder();
        key.append("item=").append(stack.getItem());
        key.append(";components=").append(stack.getComponentsPatch());
        var attachments = serializedAttachments(stack, provider);
        if (attachments != null && !attachments.isEmpty()) key.append(";attachments=").append(attachments);
        return key.toString();
    }

    private static CompoundTag serializedAttachments(ItemStack stack, HolderLookup.Provider provider) {
        if (provider == null) return null;
        return serializedAttachments((Object) stack, provider);
    }

    private static CompoundTag serializedAttachments(Object holder, HolderLookup.Provider provider) {
        if (holder instanceof AttachmentHolder attachmentHolder) try {
            return attachmentHolder.serializeAttachments(provider);
        } catch (Throwable ignored) {
            return null;
        }

        var method = ATTACHMENT_METHOD_CACHE.computeIfAbsent(holder.getClass(), cls -> {
            try {
                var m = cls.getMethod(METHOD_SERIALIZE_ATTACHMENTS, HolderLookup.Provider.class);
                if (!CompoundTag.class.isAssignableFrom(m.getReturnType())) return NO_METHOD;
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
                return NO_METHOD;
            }
        });
        if (method == NO_METHOD) return null;
        try {
            return (CompoundTag) method.invoke(holder, provider);
        } catch (Throwable ignored) {
            return null;
        }
    }
}