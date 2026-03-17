/*
 * vortex_vulkan.cpp — Vulkan display renderer implementation.
 *
 * Creates a Vulkan swapchain from the Android native window and blits
 * pre-rendered pixel data (from libretro cores) to the screen.
 * Supports both BGRA (software render) and RGBA (HW readback) input,
 * with optional Y-flip for bottom-left-origin cores.
 */
#include "vortex_vulkan.h"
#include <algorithm>
#include <cstring>

#define VK_TAG "VortexVulkan"
#define VK_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  VK_TAG, __VA_ARGS__)
#define VK_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  VK_TAG, __VA_ARGS__)
#define VK_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, VK_TAG, __VA_ARGS__)

#define VK_CHECK(expr) do {                                         \
    VkResult _r = (expr);                                           \
    if (_r != VK_SUCCESS) {                                         \
        VK_LOGE("%s failed: %d (line %d)", #expr, (int)_r, __LINE__); \
        return false;                                               \
    }                                                               \
} while(0)

/* ── Instance ──────────────────────────────────────────────────── */
bool VulkanRenderer::createInstance() {
    VkApplicationInfo appInfo = {};
    appInfo.sType              = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName   = "VortexEmulator";
    appInfo.applicationVersion = VK_MAKE_VERSION(2, 1, 0);
    appInfo.pEngineName        = "VortexFrontend";
    appInfo.engineVersion      = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion         = VK_API_VERSION_1_0;

    const char* extensions[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
    };

    VkInstanceCreateInfo ci = {};
    ci.sType                   = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo        = &appInfo;
    ci.enabledExtensionCount   = 2;
    ci.ppEnabledExtensionNames = extensions;

    VK_CHECK(vkCreateInstance(&ci, nullptr, &m_instance));
    VK_LOGI("Vulkan instance created");
    return true;
}

/* ── Surface ───────────────────────────────────────────────────── */
bool VulkanRenderer::createSurface(ANativeWindow* window) {
    VkAndroidSurfaceCreateInfoKHR ci = {};
    ci.sType  = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    ci.window = window;
    VK_CHECK(vkCreateAndroidSurfaceKHR(m_instance, &ci, nullptr, &m_surface));
    VK_LOGI("Android Vulkan surface created");
    return true;
}

/* ── Physical device ───────────────────────────────────────────── */
bool VulkanRenderer::pickPhysicalDevice() {
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(m_instance, &count, nullptr);
    if (count == 0) { VK_LOGE("No Vulkan physical devices"); return false; }

    std::vector<VkPhysicalDevice> devs(count);
    vkEnumeratePhysicalDevices(m_instance, &count, devs.data());

    for (auto& dev : devs) {
        VkPhysicalDeviceProperties props;
        vkGetPhysicalDeviceProperties(dev, &props);
        VK_LOGI("GPU: %s (type %d, driver %u)", props.deviceName,
                props.deviceType, props.driverVersion);

        uint32_t qfCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &qfCount, nullptr);
        std::vector<VkQueueFamilyProperties> qfs(qfCount);
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &qfCount, qfs.data());

        for (uint32_t i = 0; i < qfCount; i++) {
            VkBool32 present = VK_FALSE;
            vkGetPhysicalDeviceSurfaceSupportKHR(dev, i, m_surface, &present);
            if ((qfs[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) {
                m_physicalDevice = dev;
                m_queueFamily    = i;
                VK_LOGI("Selected GPU: %s (queue family %u)", props.deviceName, i);
                break;
            }
        }
        if (m_physicalDevice != VK_NULL_HANDLE) break;
    }
    if (m_physicalDevice == VK_NULL_HANDLE) {
        VK_LOGE("No GPU supports graphics + present");
        return false;
    }
    vkGetPhysicalDeviceMemoryProperties(m_physicalDevice, &m_memProps);
    return true;
}

/* ── Logical device ────────────────────────────────────────────── */
bool VulkanRenderer::createDevice() {
    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci = {};
    qci.sType            = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.queueFamilyIndex = m_queueFamily;
    qci.queueCount       = 1;
    qci.pQueuePriorities = &prio;

    const char* ext = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
    VkDeviceCreateInfo dci = {};
    dci.sType                   = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.queueCreateInfoCount    = 1;
    dci.pQueueCreateInfos       = &qci;
    dci.enabledExtensionCount   = 1;
    dci.ppEnabledExtensionNames = &ext;

    VK_CHECK(vkCreateDevice(m_physicalDevice, &dci, nullptr, &m_device));
    vkGetDeviceQueue(m_device, m_queueFamily, 0, &m_queue);
    VK_LOGI("Vulkan logical device created");
    return true;
}

/* ── Swapchain ─────────────────────────────────────────────────── */
bool VulkanRenderer::createSwapchain(int width, int height) {
    VkSwapchainKHR oldSwapchain = m_swapchain;
    m_swapchain = VK_NULL_HANDLE;

    VkSurfaceCapabilitiesKHR caps;
    VK_CHECK(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(m_physicalDevice, m_surface, &caps));

    // Pick format — prefer B8G8R8A8 (matches SW pixel layout), else R8G8B8A8
    uint32_t fmtCount;
    vkGetPhysicalDeviceSurfaceFormatsKHR(m_physicalDevice, m_surface, &fmtCount, nullptr);
    std::vector<VkSurfaceFormatKHR> fmts(fmtCount);
    vkGetPhysicalDeviceSurfaceFormatsKHR(m_physicalDevice, m_surface, &fmtCount, fmts.data());

    m_swapchainFormat = fmts[0].format;
    VkColorSpaceKHR colorSpace = fmts[0].colorSpace;
    for (auto& f : fmts) {
        if (f.format == VK_FORMAT_B8G8R8A8_UNORM ||
            f.format == VK_FORMAT_R8G8B8A8_UNORM) {
            m_swapchainFormat = f.format;
            colorSpace = f.colorSpace;
            break;
        }
    }

    // Extent
    if (caps.currentExtent.width != UINT32_MAX) {
        m_swapchainExtent = caps.currentExtent;
    } else {
        m_swapchainExtent.width  = std::clamp(static_cast<uint32_t>(width),
                                              caps.minImageExtent.width,
                                              caps.maxImageExtent.width);
        m_swapchainExtent.height = std::clamp(static_cast<uint32_t>(height),
                                              caps.minImageExtent.height,
                                              caps.maxImageExtent.height);
    }

    uint32_t imgCount = std::max(caps.minImageCount + 1, 2u);
    if (caps.maxImageCount > 0) imgCount = std::min(imgCount, caps.maxImageCount);

    // Composite alpha — pick the first supported mode
    VkCompositeAlphaFlagBitsKHR compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    if (!(caps.supportedCompositeAlpha & compositeAlpha)) {
        const VkCompositeAlphaFlagBitsKHR candidates[] = {
            VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
            VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
            VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR
        };
        for (auto c : candidates) {
            if (caps.supportedCompositeAlpha & c) { compositeAlpha = c; break; }
        }
    }

    VkSwapchainCreateInfoKHR sci = {};
    sci.sType            = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    sci.surface          = m_surface;
    sci.minImageCount    = imgCount;
    sci.imageFormat      = m_swapchainFormat;
    sci.imageColorSpace  = colorSpace;
    sci.imageExtent      = m_swapchainExtent;
    sci.imageArrayLayers = 1;
    sci.imageUsage       = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                           VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    sci.preTransform     = caps.currentTransform;
    sci.compositeAlpha   = compositeAlpha;
    sci.presentMode      = VK_PRESENT_MODE_FIFO_KHR;
    sci.clipped          = VK_TRUE;
    sci.oldSwapchain     = oldSwapchain;

    VkResult r = vkCreateSwapchainKHR(m_device, &sci, nullptr, &m_swapchain);
    if (oldSwapchain != VK_NULL_HANDLE)
        vkDestroySwapchainKHR(m_device, oldSwapchain, nullptr);
    if (r != VK_SUCCESS) {
        VK_LOGE("vkCreateSwapchainKHR failed: %d", (int)r);
        return false;
    }

    uint32_t ic;
    vkGetSwapchainImagesKHR(m_device, m_swapchain, &ic, nullptr);
    m_swapchainImages.resize(ic);
    vkGetSwapchainImagesKHR(m_device, m_swapchain, &ic, m_swapchainImages.data());

    VK_LOGI("Swapchain: %ux%u, %u images, fmt=%d",
            m_swapchainExtent.width, m_swapchainExtent.height, ic,
            (int)m_swapchainFormat);
    return true;
}

/* ── Sync objects ──────────────────────────────────────────────── */
bool VulkanRenderer::createSyncObjects() {
    VkFenceCreateInfo fci = {};
    fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fci.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    VK_CHECK(vkCreateFence(m_device, &fci, nullptr, &m_fence));

    VkSemaphoreCreateInfo sci = {};
    sci.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    VK_CHECK(vkCreateSemaphore(m_device, &sci, nullptr, &m_imageAvailable));
    VK_CHECK(vkCreateSemaphore(m_device, &sci, nullptr, &m_renderFinished));
    return true;
}

/* ── Command pool ──────────────────────────────────────────────── */
bool VulkanRenderer::createCommandPool() {
    VkCommandPoolCreateInfo pci = {};
    pci.sType            = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pci.flags            = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = m_queueFamily;
    VK_CHECK(vkCreateCommandPool(m_device, &pci, nullptr, &m_cmdPool));

    VkCommandBufferAllocateInfo ai = {};
    ai.sType              = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ai.commandPool        = m_cmdPool;
    ai.level              = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandBufferCount = 1;
    VK_CHECK(vkAllocateCommandBuffers(m_device, &ai, &m_cmdBuf));
    return true;
}

/* ── Memory helpers ────────────────────────────────────────────── */
uint32_t VulkanRenderer::findMemoryType(uint32_t typeFilter,
                                        VkMemoryPropertyFlags props) {
    for (uint32_t i = 0; i < m_memProps.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) &&
            (m_memProps.memoryTypes[i].propertyFlags & props) == props)
            return i;
    }
    return UINT32_MAX;
}

/* ── Staging buffer ────────────────────────────────────────────── */
bool VulkanRenderer::ensureStagingBuffer(VkDeviceSize size) {
    if (m_stagingSize >= size) return true;
    destroyStagingResources();

    VkBufferCreateInfo bci = {};
    bci.sType       = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bci.size        = size;
    bci.usage       = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VK_CHECK(vkCreateBuffer(m_device, &bci, nullptr, &m_stagingBuffer));

    VkMemoryRequirements mr;
    vkGetBufferMemoryRequirements(m_device, m_stagingBuffer, &mr);

    uint32_t mt = findMemoryType(mr.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (mt == UINT32_MAX) { VK_LOGE("No host-visible memory"); return false; }

    VkMemoryAllocateInfo mai = {};
    mai.sType           = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.allocationSize  = mr.size;
    mai.memoryTypeIndex = mt;
    VK_CHECK(vkAllocateMemory(m_device, &mai, nullptr, &m_stagingMemory));
    VK_CHECK(vkBindBufferMemory(m_device, m_stagingBuffer, m_stagingMemory, 0));
    VK_CHECK(vkMapMemory(m_device, m_stagingMemory, 0, size, 0, &m_stagingMap));

    m_stagingSize = size;
    return true;
}

/* ── Frame image ───────────────────────────────────────────────── */
bool VulkanRenderer::ensureFrameImage(unsigned w, unsigned h) {
    if (m_frameW == w && m_frameH == h && m_frameImage != VK_NULL_HANDLE)
        return true;
    destroyFrameImage();

    VkImageCreateInfo ici = {};
    ici.sType         = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ici.imageType     = VK_IMAGE_TYPE_2D;
    ici.format        = VK_FORMAT_R8G8B8A8_UNORM;
    ici.extent        = {w, h, 1};
    ici.mipLevels     = 1;
    ici.arrayLayers   = 1;
    ici.samples       = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling        = VK_IMAGE_TILING_OPTIMAL;
    ici.usage         = VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                        VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    ici.sharingMode   = VK_SHARING_MODE_EXCLUSIVE;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VK_CHECK(vkCreateImage(m_device, &ici, nullptr, &m_frameImage));

    VkMemoryRequirements mr;
    vkGetImageMemoryRequirements(m_device, m_frameImage, &mr);

    uint32_t mt = findMemoryType(mr.memoryTypeBits,
                                 VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (mt == UINT32_MAX) { VK_LOGE("No device-local memory"); return false; }

    VkMemoryAllocateInfo mai = {};
    mai.sType           = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.allocationSize  = mr.size;
    mai.memoryTypeIndex = mt;
    VK_CHECK(vkAllocateMemory(m_device, &mai, nullptr, &m_frameMemory));
    VK_CHECK(vkBindImageMemory(m_device, m_frameImage, m_frameMemory, 0));

    m_frameW = w;
    m_frameH = h;
    m_frameImageReady = false;
    return true;
}

/* ── Image layout transition ───────────────────────────────────── */
void VulkanRenderer::transitionImage(
    VkCommandBuffer cmd, VkImage image,
    VkImageLayout oldLayout, VkImageLayout newLayout,
    VkAccessFlags srcAccess, VkAccessFlags dstAccess,
    VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage)
{
    VkImageMemoryBarrier b = {};
    b.sType               = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.srcAccessMask       = srcAccess;
    b.dstAccessMask       = dstAccess;
    b.oldLayout           = oldLayout;
    b.newLayout           = newLayout;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image               = image;
    b.subresourceRange    = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0,
                         0, nullptr, 0, nullptr, 1, &b);
}

/* ── Destroy helpers ───────────────────────────────────────────── */
void VulkanRenderer::destroySwapchain() {
    if (m_device && m_swapchain) {
        vkDestroySwapchainKHR(m_device, m_swapchain, nullptr);
        m_swapchain = VK_NULL_HANDLE;
    }
    m_swapchainImages.clear();
}

void VulkanRenderer::destroyStagingResources() {
    if (!m_device) return;
    if (m_stagingMap)    { vkUnmapMemory(m_device, m_stagingMemory); m_stagingMap = nullptr; }
    if (m_stagingBuffer) { vkDestroyBuffer(m_device, m_stagingBuffer, nullptr); m_stagingBuffer = VK_NULL_HANDLE; }
    if (m_stagingMemory) { vkFreeMemory(m_device, m_stagingMemory, nullptr); m_stagingMemory = VK_NULL_HANDLE; }
    m_stagingSize = 0;
}

void VulkanRenderer::destroyFrameImage() {
    if (!m_device) return;
    if (m_frameImage)  { vkDestroyImage(m_device, m_frameImage, nullptr); m_frameImage = VK_NULL_HANDLE; }
    if (m_frameMemory) { vkFreeMemory(m_device, m_frameMemory, nullptr); m_frameMemory = VK_NULL_HANDLE; }
    m_frameW = m_frameH = 0;
    m_frameImageReady = false;
}

/* ── Init / Destroy ────────────────────────────────────────────── */
bool VulkanRenderer::init(ANativeWindow* window, int surfaceW, int surfaceH) {
    destroy();
    m_window = window;

    if (!createInstance())    return false;
    if (!createSurface(window)) return false;
    if (!pickPhysicalDevice()) return false;
    if (!createDevice())      return false;
    if (!createSwapchain(surfaceW, surfaceH)) return false;
    if (!createSyncObjects()) return false;
    if (!createCommandPool()) return false;

    m_ready = true;
    VK_LOGI("Vulkan renderer ready");
    return true;
}

void VulkanRenderer::destroy() {
    if (m_device) vkDeviceWaitIdle(m_device);

    destroyFrameImage();
    destroyStagingResources();

    if (m_device) {
        if (m_fence)          { vkDestroyFence(m_device, m_fence, nullptr); m_fence = VK_NULL_HANDLE; }
        if (m_imageAvailable) { vkDestroySemaphore(m_device, m_imageAvailable, nullptr); m_imageAvailable = VK_NULL_HANDLE; }
        if (m_renderFinished) { vkDestroySemaphore(m_device, m_renderFinished, nullptr); m_renderFinished = VK_NULL_HANDLE; }
        if (m_cmdPool)        { vkDestroyCommandPool(m_device, m_cmdPool, nullptr); m_cmdPool = VK_NULL_HANDLE; }
    }
    m_cmdBuf = VK_NULL_HANDLE;

    destroySwapchain();

    if (m_device)   { vkDestroyDevice(m_device, nullptr); m_device = VK_NULL_HANDLE; }
    if (m_surface && m_instance) { vkDestroySurfaceKHR(m_instance, m_surface, nullptr); m_surface = VK_NULL_HANDLE; }
    if (m_instance) { vkDestroyInstance(m_instance, nullptr); m_instance = VK_NULL_HANDLE; }

    m_physicalDevice = VK_NULL_HANDLE;
    m_queue          = VK_NULL_HANDLE;
    m_queueFamily    = UINT32_MAX;
    m_ready          = false;
    m_window         = nullptr;
}

bool VulkanRenderer::surfaceChanged(int width, int height) {
    if (!m_device) return false;
    vkDeviceWaitIdle(m_device);
    return createSwapchain(width, height);
}

/* ── Present frame ─────────────────────────────────────────────── */
bool VulkanRenderer::presentFrame(const uint32_t* pixels, unsigned w, unsigned h,
                                  bool isBGRA, bool flipY) {
    if (!m_ready || !pixels || w == 0 || h == 0) return false;
    if (m_device == VK_NULL_HANDLE || m_swapchain == VK_NULL_HANDLE ||
        m_fence == VK_NULL_HANDLE || m_cmdBuf == VK_NULL_HANDLE) {
        return false;
    }

    // 1. Wait for previous frame
    vkWaitForFences(m_device, 1, &m_fence, VK_TRUE, UINT64_MAX);

    // 2. Acquire swapchain image
    uint32_t imgIdx;
    VkResult acq = vkAcquireNextImageKHR(m_device, m_swapchain, UINT64_MAX,
                                         m_imageAvailable, VK_NULL_HANDLE, &imgIdx);
    if (acq == VK_ERROR_OUT_OF_DATE_KHR) {
        surfaceChanged(m_swapchainExtent.width, m_swapchainExtent.height);
        return false;
    }
    if (acq != VK_SUCCESS && acq != VK_SUBOPTIMAL_KHR) return false;

    vkResetFences(m_device, 1, &m_fence);

    // 3. Ensure staging buffer + frame image
    VkDeviceSize bytes = static_cast<VkDeviceSize>(w) * h * 4;
    if (!ensureStagingBuffer(bytes)) return false;
    if (!ensureFrameImage(w, h))     return false;

    // 4. Upload pixels to staging buffer
    //    Frame image is R8G8B8A8_UNORM → bytes [R,G,B,A].
    //    SW path (isBGRA): input = 0xFFRRGGBB LE → bytes [B,G,R,0xFF] → swap R↔B.
    //    HW path: input = RGBA from glReadPixels → direct copy.
    auto* dst = static_cast<uint32_t*>(m_stagingMap);
    size_t count = static_cast<size_t>(w) * h;
    if (isBGRA) {
        for (size_t i = 0; i < count; i++) {
            uint32_t p = pixels[i];
            uint32_t r = (p >> 16) & 0xFF;
            uint32_t b = p & 0xFF;
            dst[i] = (p & 0xFF00FF00u) | (b << 16) | r;
        }
    } else {
        std::memcpy(dst, pixels, bytes);
    }

    // 5. Record command buffer
    vkResetCommandBuffer(m_cmdBuf, 0);
    VkCommandBufferBeginInfo bi = {};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(m_cmdBuf, &bi);

    // Frame image: UNDEFINED/TRANSFER_SRC → TRANSFER_DST
    transitionImage(m_cmdBuf, m_frameImage,
        m_frameImageReady ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                          : VK_IMAGE_LAYOUT_UNDEFINED,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        m_frameImageReady ? VK_ACCESS_TRANSFER_READ_BIT : 0,
        VK_ACCESS_TRANSFER_WRITE_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT);

    // Copy staging → frame image
    VkBufferImageCopy region = {};
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageExtent      = {w, h, 1};
    vkCmdCopyBufferToImage(m_cmdBuf, m_stagingBuffer, m_frameImage,
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    // Frame image → TRANSFER_SRC
    transitionImage(m_cmdBuf, m_frameImage,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    m_frameImageReady = true;

    // Swapchain image → TRANSFER_DST
    VkImage swapImg = m_swapchainImages[imgIdx];
    transitionImage(m_cmdBuf, swapImg,
        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        0, VK_ACCESS_TRANSFER_WRITE_BIT,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    // Clear swapchain to black (letterbox bars)
    VkClearColorValue black = {{0.0f, 0.0f, 0.0f, 1.0f}};
    VkImageSubresourceRange clearRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdClearColorImage(m_cmdBuf, swapImg,
                         VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                         &black, 1, &clearRange);

    // Barrier: clear write → blit write on same image
    VkMemoryBarrier mb = {};
    mb.sType         = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(m_cmdBuf,
        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
        0, 1, &mb, 0, nullptr, 0, nullptr);

    // Compute letterboxed destination (aspect-ratio preserving)
    float srcAR = static_cast<float>(w) / static_cast<float>(h);
    float dstAR = static_cast<float>(m_swapchainExtent.width) /
                  static_cast<float>(m_swapchainExtent.height);
    int32_t dX = 0, dY = 0;
    int32_t dW = static_cast<int32_t>(m_swapchainExtent.width);
    int32_t dH = static_cast<int32_t>(m_swapchainExtent.height);
    if (srcAR > dstAR) {
        dH = static_cast<int32_t>(dW / srcAR);
        dY = (static_cast<int32_t>(m_swapchainExtent.height) - dH) / 2;
    } else {
        dW = static_cast<int32_t>(dH * srcAR);
        dX = (static_cast<int32_t>(m_swapchainExtent.width) - dW) / 2;
    }

    // Blit frame → swapchain (with optional Y-flip via src offsets)
    VkImageBlit blit = {};
    blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    blit.srcOffsets[0]  = {0, flipY ? static_cast<int32_t>(h) : 0, 0};
    blit.srcOffsets[1]  = {static_cast<int32_t>(w),
                           flipY ? 0 : static_cast<int32_t>(h), 1};
    blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    blit.dstOffsets[0]  = {dX, dY, 0};
    blit.dstOffsets[1]  = {dX + dW, dY + dH, 1};

    vkCmdBlitImage(m_cmdBuf,
        m_frameImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        swapImg, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        1, &blit, VK_FILTER_LINEAR);

    // Swapchain image → PRESENT_SRC
    transitionImage(m_cmdBuf, swapImg,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);

    vkEndCommandBuffer(m_cmdBuf);

    // 6. Submit
    VkPipelineStageFlags wait = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo si = {};
    si.sType                = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.waitSemaphoreCount   = 1;
    si.pWaitSemaphores      = &m_imageAvailable;
    si.pWaitDstStageMask    = &wait;
    si.commandBufferCount   = 1;
    si.pCommandBuffers      = &m_cmdBuf;
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores    = &m_renderFinished;

    VkResult sub = vkQueueSubmit(m_queue, 1, &si, m_fence);
    if (sub != VK_SUCCESS) { VK_LOGE("vkQueueSubmit: %d", (int)sub); return false; }

    // 7. Present
    VkPresentInfoKHR pi = {};
    pi.sType              = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores    = &m_renderFinished;
    pi.swapchainCount     = 1;
    pi.pSwapchains        = &m_swapchain;
    pi.pImageIndices      = &imgIdx;

    VkResult pres = vkQueuePresentKHR(m_queue, &pi);
    if (pres == VK_ERROR_OUT_OF_DATE_KHR || pres == VK_SUBOPTIMAL_KHR)
        surfaceChanged(m_swapchainExtent.width, m_swapchainExtent.height);

    return true;
}
